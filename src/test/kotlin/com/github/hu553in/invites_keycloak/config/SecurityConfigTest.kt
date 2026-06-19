package com.github.hu553in.invites_keycloak.config

import com.github.hu553in.invites_keycloak.InvitesKeycloakApplication
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestConstructor
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.*
import com.github.tomakehurst.wiremock.client.WireMock.get as wireMockGet
import com.github.tomakehurst.wiremock.client.WireMock.post as wireMockPost

@SpringBootTest(
    classes = [InvitesKeycloakApplication::class, SecurityConfigTest.TestAdminController::class],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityConfigTest(private val mockMvc: MockMvc, private val clock: Clock) {

    companion object {
        private const val REQUIRED_ROLE = "invite-admin"
        private const val CLIENT_ID = "test-client"
        private const val REALM = "master"

        private val server = WireMockServer(WireMockConfiguration.options().dynamicPort())

        private val rsaJwk: RSAKey = RSAKeyGenerator(2048)
            .keyID("test-key")
            .generate()

        private val jwkSet: JWKSet = JWKSet(rsaJwk.toPublicJWK())

        private val issuer: String
            get() = "${server.baseUrl()}/realms/$REALM"

        private val tokenPath: String
            get() = "/realms/$REALM/protocol/openid-connect/token"

        private val userInfoPath: String
            get() = "/realms/$REALM/protocol/openid-connect/userinfo"

        private val jwkSetPath: String
            get() = "/realms/$REALM/protocol/openid-connect/certs"

        private fun startWireMock() {
            if (!server.isRunning) {
                server.start()
            }
        }

        @JvmStatic
        @DynamicPropertySource
        fun registerProps(registry: DynamicPropertyRegistry) {
            startWireMock()
            registry.add("keycloak.url") { server.baseUrl() }
            registry.add("keycloak.client-id") { CLIENT_ID }
            registry.add("keycloak.client-secret") { "test-secret" }
            registry.add("keycloak.required-role") { REQUIRED_ROLE }
        }
    }

    @AfterAll
    fun stopWireMock() {
        server.stop()
    }

    @BeforeEach
    fun setUp() {
        server.resetAll()
        startWireMock()

        server.stubFor(
            wireMockGet(urlEqualTo(jwkSetPath))
                .willReturn(okJson(jwkSet.toString())),
        )
    }

    @Test
    fun `unauthenticated user hitting admin area is redirected to Keycloak`() {
        // arrange
        val session = MockHttpSession()

        // act
        val initial = mockMvc.perform(get("/admin/test").session(session))
            // assert
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/oauth2/authorization/keycloak"))
            .andReturn()

        // act
        val authRedirect = mockMvc.perform(get(initial.response.redirectedUrl!!).session(session))
            // assert
            .andExpect(status().is3xxRedirection)
            .andReturn()

        val location = authRedirect.response.getHeader(HttpHeaders.LOCATION)
        assertThat(location).isNotNull()

        val uri = URI(location!!)
        assertThat(uri.toString()).startsWith("${server.baseUrl()}/realms/master/protocol/openid-connect/auth")
    }

    @Test
    fun `user without required role receives 403 after login`() {
        // arrange
        val ctx = initiateAuthorizationFlow()

        val subject = stubTokenResponse(ctx.nonce, emptySet())
        stubUserInfoResponse(emptySet(), subject)

        // act
        val loginResult = mockMvc.perform(
            get("/login/oauth2/code/keycloak")
                .session(ctx.session)
                .withCookiesIfPresent(ctx.cookies)
                .param("code", "test-code")
                .param("state", ctx.state),
        )
            // assert
            .andExpect(status().is3xxRedirection)
            .andReturn()

        val authenticatedSession = loginResult.request.session as MockHttpSession
        val authenticatedCookies = mergeCookies(ctx.cookies, loginResult.response.cookies.toList())

        // act
        mockMvc.perform(
            get("/admin/test")
                .session(authenticatedSession)
                .withCookiesIfPresent(authenticatedCookies),
        )
            // assert
            .andExpect(status().isForbidden)
    }

    @Test
    fun `user with required role gains access to admin area`() {
        // arrange
        val ctx = initiateAuthorizationFlow()

        val subject = stubTokenResponse(ctx.nonce, setOf(REQUIRED_ROLE))
        stubUserInfoResponse(setOf(REQUIRED_ROLE), subject)

        // act
        val loginResult = mockMvc.perform(
            get("/login/oauth2/code/keycloak")
                .session(ctx.session)
                .withCookiesIfPresent(ctx.cookies)
                .param("code", "test-code")
                .param("state", ctx.state),
        )
            // assert
            .andExpect(status().is3xxRedirection)
            .andReturn()

        val authenticatedSession = loginResult.request.session as MockHttpSession
        val authenticatedCookies = mergeCookies(ctx.cookies, loginResult.response.cookies.toList())

        // act
        mockMvc.perform(
            get("/admin/test")
                .session(authenticatedSession)
                .withCookiesIfPresent(authenticatedCookies),
        )
            // assert
            .andExpect(status().isOk)
    }

    @Test
    fun `authenticated user is redirected to Keycloak logout endpoint on logout`() {
        // arrange
        val authenticated = loginWithRoles(setOf(REQUIRED_ROLE))

        // act
        val logoutResult = mockMvc.perform(
            post("/logout")
                .session(authenticated.session)
                .withCookiesIfPresent(authenticated.cookies)
                .with(csrf()),
        )
            // assert
            .andExpect(status().is3xxRedirection)
            .andReturn()

        val location = logoutResult.response.getHeader(HttpHeaders.LOCATION)
        assertThat(location).isNotBlank()
        assertThat(location).startsWith("${server.baseUrl()}/realms/$REALM/protocol/openid-connect/logout")

        val uri = URI(location!!)
        val params = UriComponentsBuilder.fromUri(uri).build().queryParams

        assertThat(params.getFirst("post_logout_redirect_uri")).isEqualTo("http://localhost/")
        assertThat(params.getFirst("id_token_hint")).isNotBlank()
    }

    @Test
    fun `public invite endpoint stays accessible`() {
        // act
        mockMvc.perform(get("/invite/anything"))
            // assert
            .andExpect(status().isNotFound)
    }

    @Test
    fun `public invite redeem requires csrf`() {
        // act
        mockMvc.perform(
            post("/invite/master/token")
                .param("challenge", "test-challenge"),
        )
            // assert
            .andExpect(status().isForbidden)
    }

    @Test
    fun `public invite redeem reaches application with csrf`() {
        // act
        mockMvc.perform(
            post("/invite/master/token")
                .param("challenge", "test-challenge")
                .with(csrf()),
        )
            // assert
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `favicon endpoint stays public`() {
        // act
        val result = mockMvc.perform(get("/favicon.ico"))
            // assert
            .andExpect(status().isOk)
            .andReturn()

        assertThat(result.response.contentType).isEqualTo("image/x-icon")
        assertThat(result.response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("max-age=3600")
        assertThat(result.response.contentAsByteArray).isNotEmpty()
    }

    @Test
    fun `robots endpoint stays public`() {
        // act
        val result = mockMvc.perform(get("/robots.txt"))
            // assert
            .andExpect(status().isOk)
            .andReturn()

        assertThat(result.response.contentType).isEqualTo(MediaType.TEXT_PLAIN_VALUE)
        assertThat(result.response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("max-age=3600")
        assertThat(result.response.contentAsString).isEqualTo("User-agent: *\nDisallow: /\n")
    }

    @Test
    fun `actuator health is public`() {
        // act
        mockMvc.perform(get("/actuator/health"))
            // assert
            .andExpect(status().isOk)
    }

    @Test
    fun `actuator env requires role`() {
        // unauthenticated -> redirected to login page
        // act
        mockMvc.perform(get("/actuator/env"))
            // assert
            .andExpect(status().is3xxRedirection)

        // authenticated without role -> forbidden
        // arrange
        val noRole = loginWithRoles(emptySet())
        // act
        mockMvc.perform(
            get("/actuator/env")
                .session(noRole.session)
                .withCookiesIfPresent(noRole.cookies),
        )
            // assert
            .andExpect(status().isForbidden)

        // authenticated with role -> ok
        // arrange
        val admin = loginWithRoles(setOf(REQUIRED_ROLE))
        // act
        mockMvc.perform(
            get("/actuator/env")
                .session(admin.session)
                .withCookiesIfPresent(admin.cookies),
        )
            // assert
            .andExpect(status().isOk)
    }

    private fun initiateAuthorizationFlow(): AuthorizationContext {
        // arrange
        val session = MockHttpSession()

        // act
        val initial = mockMvc.perform(get("/admin/test").session(session))
            // assert
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/oauth2/authorization/keycloak"))
            .andReturn()

        // act
        val authorize = mockMvc.perform(get(initial.response.redirectedUrl!!).session(session))
            // assert
            .andExpect(status().is3xxRedirection)
            .andReturn()

        val location = authorize.response.getHeader(HttpHeaders.LOCATION)!!
        val uri = UriComponentsBuilder.fromUriString(location).build()

        val state = uri.queryParams.getFirst("state")!!.decode()
        val nonce = uri.queryParams.getFirst("nonce")!!.decode()

        val cookies = authorize.response.cookies
        return AuthorizationContext(session, state, nonce, cookies.toList())
    }

    private fun loginWithRoles(roles: Set<String>): AuthenticatedContext {
        // arrange
        val ctx = initiateAuthorizationFlow()

        val subject = stubTokenResponse(ctx.nonce, roles)
        stubUserInfoResponse(roles, subject)

        // act
        val loginResult = mockMvc.perform(
            get("/login/oauth2/code/keycloak")
                .session(ctx.session)
                .withCookiesIfPresent(ctx.cookies)
                .param("code", "test-code")
                .param("state", ctx.state),
        )
            // assert
            .andExpect(status().is3xxRedirection)
            .andReturn()

        val authenticatedSession = loginResult.request.session as MockHttpSession
        val authenticatedCookies = mergeCookies(ctx.cookies, loginResult.response.cookies.toList())

        return AuthenticatedContext(authenticatedSession, authenticatedCookies)
    }

    private fun stubTokenResponse(nonce: String, roles: Set<String>): String {
        val subject = "user-${UUID.randomUUID()}"
        val idToken = buildIdToken(nonce, roles, subject)
        server.stubFor(
            wireMockPost(urlEqualTo(tokenPath))
                .withHeader(HttpHeaders.CONTENT_TYPE, containing(MediaType.APPLICATION_FORM_URLENCODED_VALUE))
                .withRequestBody(containing("grant_type=authorization_code"))
                .withRequestBody(containing("code=test-code"))
                .withRequestBody(containing("client_id=$CLIENT_ID"))
                .willReturn(
                    aResponse()
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody(
                            """
                            {
                              "access_token": "test-access-token",
                              "token_type": "Bearer",
                              "expires_in": 60,
                              "id_token": "$idToken"
                            }
                            """.trimIndent(),
                        ),
                ),
        )
        return subject
    }

    private fun stubUserInfoResponse(roles: Set<String>, subject: String) {
        val response = """
            {
              "sub": "$subject",
              "preferred_username": "invite-admin",
              "realm_access": {
                "roles": ${roles.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}
              }
            }
        """.trimIndent()

        server.stubFor(
            wireMockGet(urlEqualTo(userInfoPath))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer test-access-token"))
                .willReturn(
                    aResponse()
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody(response),
                ),
        )
    }

    private fun buildIdToken(nonce: String, roles: Set<String>, subject: String): String {
        val now = clock.instant()
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(subject)
            .audience(CLIENT_ID)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(60)))
            .claim("nonce", nonce)
            .claim("preferred_username", "invite-admin")
            .claim("realm_access", mapOf("roles" to roles))
            .build()
        val signed = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaJwk.keyID).build(),
            claims,
        )
        signed.sign(RSASSASigner(rsaJwk.toPrivateKey()))
        return signed.serialize()
    }

    private data class AuthorizationContext(
        val session: MockHttpSession,
        val state: String,
        val nonce: String,
        val cookies: List<Cookie>,
    )

    private data class AuthenticatedContext(val session: MockHttpSession, val cookies: List<Cookie>)

    @RestController
    @RequestMapping("/admin/test")
    class TestAdminController {
        @GetMapping
        fun get(): Map<String, String> = mapOf("status" to "ok")
    }
}

private fun MockHttpServletRequestBuilder.withCookiesIfPresent(cookies: List<Cookie>): MockHttpServletRequestBuilder =
    if (cookies.isEmpty()) {
        this
    } else {
        this.cookie(*cookies.toTypedArray())
    }

private fun String.decode(): String = URLDecoder.decode(this, StandardCharsets.UTF_8)

private fun mergeCookies(initial: List<Cookie>, additional: List<Cookie>?): List<Cookie> {
    if (additional?.isEmpty() ?: true) {
        return initial
    }
    val merged = LinkedHashMap<String, Cookie>(initial.size + additional.size)
    initial.forEach { merged[it.name] = it }
    additional.forEach { merged[it.name] = it }
    return merged.values.toList()
}
