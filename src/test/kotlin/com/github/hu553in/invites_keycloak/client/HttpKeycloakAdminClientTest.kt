package com.github.hu553in.invites_keycloak.client

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.hu553in.invites_keycloak.config.props.KeycloakProps
import com.github.hu553in.invites_keycloak.exception.KeycloakAdminClientException
import com.github.hu553in.invites_keycloak.util.KEYCLOAK_OPERATION_KEY
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.noContent
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.put
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpKeycloakAdminClientTest {

    private lateinit var server: WireMockServer
    private lateinit var client: KeycloakAdminClient
    private lateinit var clock: MutableClock
    private lateinit var listAppender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger
    private var previousLoggerLevel: Level? = null

    @BeforeAll
    fun setupSuite() {
        server = WireMockServer(WireMockConfiguration.options().dynamicPort())
        server.start()
    }

    @AfterAll
    fun tearDownSuite() {
        server.stop()
    }

    @BeforeEach
    fun setup() {
        server.resetAll()
        stubToken()
        clock = MutableClock(Instant.parse("2025-01-01T00:00:00Z"))

        client = HttpKeycloakAdminClient(
            keycloakProps = KeycloakProps(
                url = server.baseUrl(),
                realm = "master",
                clientId = "admin-cli",
                clientSecret = "s3cr3t",
                requiredRole = "invite-admin",
                maxAttempts = 3,
                minBackoff = Duration.ofMillis(10),
                connectTimeout = Duration.ofSeconds(1),
                responseTimeout = Duration.ofSeconds(2)
            ),
            clock = clock,
            webClientBuilder = WebClient.builder()
        )

        logger = LoggerFactory.getLogger(HttpKeycloakAdminClient::class.java) as Logger
        previousLoggerLevel = logger.level
        logger.level = Level.DEBUG
        listAppender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(listAppender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(listAppender)
        logger.level = previousLoggerLevel
    }

    @Test
    fun `userExists returns true when Keycloak finds user`() {
        // arrange
        server.stubFor(
            get(urlPathEqualTo("/admin/realms/invite-realm/users"))
                .withQueryParam("email", equalTo("user@example.com"))
                .withQueryParam("exact", equalTo("true"))
                .withQueryParam("max", equalTo("1"))
                .withQueryParam("briefRepresentation", equalTo("true"))
                .withHeader("Authorization", equalTo("Bearer admin-token"))
                .willReturn(okJson("""[{"id":"user-id","email":"user@example.com"}]"""))
        )

        // act
        val exists = client.userExists("invite-realm", "user@example.com")

        // assert
        assertThat(exists).isTrue()
        server.verify(
            1,
            postRequestedFor(urlEqualTo("/realms/master/protocol/openid-connect/token"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withRequestBody(containing("client_id=admin-cli"))
                .withRequestBody(containing("client_secret=s3cr3t"))
        )
    }

    @Test
    fun `userExists returns false when Keycloak returns empty array`() {
        // arrange
        server.stubFor(
            get(urlPathEqualTo("/admin/realms/invite-realm/users"))
                .withQueryParam("email", equalTo("missing@example.com"))
                .withQueryParam("exact", equalTo("true"))
                .withQueryParam("max", equalTo("1"))
                .withQueryParam("briefRepresentation", equalTo("true"))
                .withHeader("Authorization", equalTo("Bearer admin-token"))
                .willReturn(okJson("[]"))
        )

        // act
        val exists = client.userExists("invite-realm", "missing@example.com")

        // assert
        assertThat(exists).isFalse()
    }

    @Test
    fun `createUser returns id parsed from Location header`() {
        // arrange
        server.stubFor(
            post(urlEqualTo("/admin/realms/invite-realm/users"))
                .withHeader("Authorization", equalTo("Bearer admin-token"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(containing("\"email\":\"user@example.com\""))
                .withRequestBody(containing("\"username\":\"user1\""))
                .withRequestBody(containing("\"enabled\":true"))
                .willReturn(
                    aResponse()
                        .withStatus(201)
                        .withHeader(
                            "Location",
                            "${server.baseUrl()}/admin/realms/invite-realm/users/9c4ed0cb-b68c-4f83-8f06-3a9aa94b8b1a"
                        )
                )
        )

        // act
        val userId = client.createUser("invite-realm", "user@example.com", "user1")

        // assert
        assertThat(userId).isEqualTo("9c4ed0cb-b68c-4f83-8f06-3a9aa94b8b1a")
    }

    @Test
    fun `createUser throws on 409 conflict`() {
        // arrange
        server.stubFor(
            post(urlEqualTo("/admin/realms/invite-realm/users"))
                .withHeader("Authorization", equalTo("Bearer admin-token"))
                .withHeader("Content-Type", containing("application/json"))
                .willReturn(aResponse().withStatus(409))
        )

        // act
        assertThatThrownBy { client.createUser("invite-realm", "user@example.com", "user1") }
            // assert
            .isInstanceOf(KeycloakAdminClientException::class.java)
            .hasMessageContaining("already exists")
    }

    @Test
    fun `userExists wraps webclient response exception with status and cause`() {
        // arrange
        server.stubFor(
            get(urlPathEqualTo("/admin/realms/invite-realm/users"))
                .withQueryParam("email", equalTo("user@example.com"))
                .withQueryParam("exact", equalTo("true"))
                .withQueryParam("max", equalTo("1"))
                .withQueryParam("briefRepresentation", equalTo("true"))
                .withHeader("Authorization", equalTo("Bearer admin-token"))
                .willReturn(aResponse().withStatus(429).withStatusMessage("Too Many Requests"))
        )

        // act
        assertThatThrownBy { client.userExists("invite-realm", "user@example.com") }
            // assert
            .isInstanceOfSatisfying(KeycloakAdminClientException::class.java) { e ->
                assertThat(e.statusCode?.value()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value())
                assertThat(e.cause).isInstanceOf(WebClientResponseException::class.java)
            }
    }

    @Test
    fun `userExists wraps webclient request exception with cause when network fails`() {
        // arrange
        server.stubFor(
            get(urlPathEqualTo("/admin/realms/invite-realm/users"))
                .withQueryParam("email", equalTo("user@example.com"))
                .withQueryParam("exact", equalTo("true"))
                .withQueryParam("max", equalTo("1"))
                .withQueryParam("briefRepresentation", equalTo("true"))
                .withHeader("Authorization", equalTo("Bearer admin-token"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
        )

        // act
        assertThatThrownBy { client.userExists("invite-realm", "user@example.com") }
            // assert
            .isInstanceOfSatisfying(KeycloakAdminClientException::class.java) { e ->
                assertThat(e.statusCode).isNull()
                assertThat(e.cause).isInstanceOf(WebClientRequestException::class.java)
            }
    }

    @Test
    fun `assignRealmRoles resolves roles and posts mapping`() {
        // arrange
        server.stubFor(
            get(urlEqualTo("/admin/realms/invite-realm/roles/invite-manager"))
                .withHeader("Authorization", equalTo("Bearer admin-token"))
                .willReturn(okJson("""{"id":"role-id-1","name":"invite-manager"}"""))
        )
        server.stubFor(
            get(urlEqualTo("/admin/realms/invite-realm/roles/reviewer"))
                .withHeader("Authorization", equalTo("Bearer admin-token"))
                .willReturn(okJson("""{"id":"role-id-2","name":"reviewer"}"""))
        )
        server.stubFor(
            post(urlEqualTo("/admin/realms/invite-realm/users/user-id/role-mappings/realm"))
                .withHeader("Authorization", equalTo("Bearer admin-token"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(containing("\"name\":\"invite-manager\""))
                .withRequestBody(containing("\"name\":\"reviewer\""))
                .willReturn(aResponse().withStatus(204))
        )

        // act
        client.assignRealmRoles(
            realm = "invite-realm",
            userId = "user-id",
            roles = setOf("invite-manager", "reviewer", "invite-manager") // duplicate filtered by Set on caller side
        )

        // assert
        server.verify(1, postRequestedFor(urlEqualTo("/realms/master/protocol/openid-connect/token")))
    }

    @Test
    fun `assignRealmRoles no-ops when roles set is empty`() {
        // act
        client.assignRealmRoles(
            realm = "invite-realm",
            userId = "user-id",
            roles = emptySet()
        )

        // assert
        server.verify(0, postRequestedFor(urlEqualTo("/realms/master/protocol/openid-connect/token")))
        server.verify(
            0,
            postRequestedFor(urlEqualTo("/admin/realms/invite-realm/users/user-id/role-mappings/realm"))
        )
    }

    @Test
    fun `assignRealmRoles throws when role is not found`() {
        // arrange
        server.stubFor(
            get(urlEqualTo("/admin/realms/invite-realm/roles/missing-role"))
                .withHeader("Authorization", equalTo("Bearer admin-token"))
                .willReturn(aResponse().withStatus(404))
        )

        // act
        assertThatThrownBy { client.assignRealmRoles("invite-realm", "user-id", setOf("missing-role")) }
            // assert
            .isInstanceOf(KeycloakAdminClientException::class.java)
            .hasMessageContaining("not found")
    }

    @Test
    fun `assignRealmRoles throws when role representation is incomplete`() {
        // arrange
        server.stubFor(
            get(urlEqualTo("/admin/realms/invite-realm/roles/broken-role"))
                .withHeader("Authorization", equalTo("Bearer admin-token"))
                .willReturn(okJson("""{"id":"","name":"broken-role"}"""))
        )

        // act
        assertThatThrownBy { client.assignRealmRoles("invite-realm", "user-id", setOf("broken-role")) }
            // assert
            .isInstanceOfSatisfying(KeycloakAdminClientException::class.java) { e ->
                assertThat(e.statusCode?.value()).isEqualTo(HttpStatus.BAD_REQUEST.value())
                assertThat(e.message).contains("incomplete representation")
            }
    }

    @Test
    fun `executeActionsEmail sends request with actions`() {
        // arrange
        server.stubFor(
            put(urlEqualTo("/admin/realms/invite-realm/users/user-id/execute-actions-email"))
                .withHeader("Authorization", equalTo("Bearer admin-token"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(containing("UPDATE_PASSWORD"))
                .withRequestBody(containing("VERIFY_EMAIL"))
                .willReturn(aResponse().withStatus(204))
        )

        // act
        client.executeActionsEmail("invite-realm", "user-id")

        // assert
        server.verify(1, postRequestedFor(urlEqualTo("/realms/master/protocol/openid-connect/token")))
    }

    @Test
    fun `listRealmRoles returns sorted unique names`() {
        // arrange
        server.stubFor(
            get(urlPathEqualTo("/admin/realms/invite-realm/roles"))
                .withQueryParam("first", equalTo("0"))
                .withQueryParam("max", equalTo("1000"))
                .withHeader("Authorization", equalTo("Bearer admin-token"))
                .willReturn(
                    okJson(
                        """
                        [
                            {"id":"1", "name":"viewer"},
                            {"id":"2", "name":"admin"},
                            {"id":"3", "name":"viewer"}
                        ]
                        """.trimIndent()
                    )
                )
        )

        // act
        val roles = client.listRealmRoles("invite-realm")

        // assert
        assertThat(roles).containsExactly("admin", "viewer")
    }

    @Test
    fun `listRealmRoles paginates when realm has many roles`() {
        // arrange
        server.stubFor(
            get(urlPathEqualTo("/admin/realms/huge-realm/roles"))
                .withQueryParam("first", equalTo("0"))
                .withQueryParam("max", equalTo("1000"))
                .withHeader("Authorization", equalTo("Bearer admin-token"))
                .willReturn(okJson(roleArrayJson(1000, "role")))
        )
        server.stubFor(
            get(urlPathEqualTo("/admin/realms/huge-realm/roles"))
                .withQueryParam("first", equalTo("1000"))
                .withQueryParam("max", equalTo("1000"))
                .withHeader("Authorization", equalTo("Bearer admin-token"))
                .willReturn(okJson("""[{"id":"x","name":"extra-role"}]"""))
        )

        // act
        val roles = client.listRealmRoles("huge-realm")

        // assert
        assertThat(roles).contains("role-0", "role-999", "extra-role")
        assertThat(roles).doesNotHaveDuplicates()
    }

    @Test
    fun `deleteUser removes user`() {
        // arrange
        stubToken()
        server.stubFor(
            delete(urlEqualTo("/admin/realms/master/users/uid"))
                .withHeader("Authorization", equalTo("Bearer admin-token"))
                .willReturn(noContent())
        )

        // act
        client.deleteUser("master", "uid")

        // assert
        server.verify(1, deleteRequestedFor(urlEqualTo("/admin/realms/master/users/uid")))
    }

    @Test
    fun `obtainAccessToken reuses cached token while outside skew window`() {
        // arrange
        stubToken(token = "t1", expiresIn = 300)
        stubUserExists(realm = "reuse-realm", token = "t1")

        // act
        client.userExists("reuse-realm", "user@example.com")
        advanceSeconds(100)
        client.userExists("reuse-realm", "user@example.com")

        // assert
        server.verify(1, postRequestedFor(urlEqualTo("/realms/master/protocol/openid-connect/token")))
    }

    @Test
    fun `obtainAccessToken returns cached token when refresh fails inside skew`() {
        // arrange
        stubTokenScenario(
            firstToken = "t-refresh",
            firstExpiresIn = 30,
            secondStatus = 500
        )
        stubUserExists(realm = "skew-realm", token = "t-refresh")

        // act
        client.userExists("skew-realm", "user@example.com")
        advanceSeconds(26) // inside skew window (remaining 4s, skew 5s)
        val exists = client.userExists("skew-realm", "user@example.com")

        // assert
        assertThat(exists).isTrue()
        val tokenCalls = server
            .findAll(postRequestedFor(urlEqualTo("/realms/master/protocol/openid-connect/token")))
            .size
        assertThat(tokenCalls).isGreaterThanOrEqualTo(2)
    }

    @Test
    fun `obtainAccessToken logs warn when refresh fails and cached token is reused inside skew`() {
        // arrange
        stubTokenScenario(
            firstToken = "t-warn",
            firstExpiresIn = 30,
            secondStatus = 500
        )
        stubUserExists(realm = "skew-warn-realm", token = "t-warn")

        // act
        client.userExists("skew-warn-realm", "user@example.com")
        advanceSeconds(26)
        val exists = client.userExists("skew-warn-realm", "user@example.com")

        // assert
        assertThat(exists).isTrue()
        val event = listAppender.list.first {
            it.formattedMessage == "Failed to refresh Keycloak access token; using cached token within skew window"
        }
        assertThat(event.level).isEqualTo(Level.WARN)
        assertThat(event.mdcPropertyMap).containsEntry(KEYCLOAK_OPERATION_KEY, "obtain_access_token")
    }

    @Test
    fun `obtainAccessToken logs debug when refresh lock is contended and cached token is reused within skew`() {
        // arrange
        stubTokenScenario(
            firstToken = "t-lock",
            firstExpiresIn = 30,
            secondStatus = 200,
            secondToken = "unused"
        )
        stubUserExists(realm = "lock-realm", token = "t-lock")
        client.userExists("lock-realm", "user@example.com")
        advanceSeconds(26)

        val lockField = client.javaClass.getDeclaredField("cachedAccessTokenWriteLock").apply { isAccessible = true }
        val lock = lockField.get(client) as java.util.concurrent.locks.Lock
        val cachedTokenField = client.javaClass.getDeclaredField("cachedAccessToken").apply { isAccessible = true }
        val cachedWithinSkew = cachedTokenField.get(client)
        val refreshIfPossible = client.javaClass.declaredMethods
            .first { it.name == "refreshIfPossible" }
            .apply { isAccessible = true }
        val lockedLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val locker = Thread {
            lock.lock()
            try {
                lockedLatch.countDown()
                releaseLatch.await(5, TimeUnit.SECONDS)
            } finally {
                lock.unlock()
            }
        }
        locker.start()
        assertThat(lockedLatch.await(5, TimeUnit.SECONDS)).isTrue()

        // act
        try {
            val token = refreshIfPossible.invoke(client, cachedWithinSkew) as String

            // assert
            assertThat(token).isEqualTo("t-lock")
        } finally {
            releaseLatch.countDown()
            locker.join(5000)
        }

        val event = listAppender.list.first {
            it.formattedMessage == "Using cached Keycloak access token within skew window"
        }
        assertThat(event.level).isEqualTo(Level.DEBUG)
        server.verify(1, postRequestedFor(urlEqualTo("/realms/master/protocol/openid-connect/token")))
    }

    @Test
    fun `obtainAccessToken fetches new token after expiry`() {
        // arrange
        stubTokenScenario(
            firstToken = "t-old",
            firstExpiresIn = 10,
            secondToken = "t-new",
            secondStatus = 200
        )
        stubUserExists(realm = "exp-realm", token = "t-old")
        stubUserExists(realm = "exp-realm", token = "t-new")

        // act
        client.userExists("exp-realm", "user@example.com")
        advanceSeconds(11) // token expired
        client.userExists("exp-realm", "user@example.com")

        // assert
        server.verify(2, postRequestedFor(urlEqualTo("/realms/master/protocol/openid-connect/token")))
        server.verify(
            1,
            getRequestedFor(urlPathEqualTo("/admin/realms/exp-realm/users"))
                .withHeader("Authorization", equalTo("Bearer t-new"))
        )
    }

    @Test
    fun `obtainAccessToken throws with preserved cause when retries exhausted`() {
        // arrange
        server.resetAll()
        server.stubFor(
            post(urlEqualTo("/realms/master/protocol/openid-connect/token"))
                .willReturn(aResponse().withStatus(500))
        )

        // act
        assertThatThrownBy { client.userExists("retry-realm", "user@example.com") }
            // assert
            .isInstanceOfSatisfying(KeycloakAdminClientException::class.java) { e ->
                assertThat(e.statusCode?.value()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value())
                assertThat(e.cause).isNotNull()
            }
    }

    @Test
    fun `failed token refresh after expiry clears cached token and rethrows`() {
        // arrange
        stubTokenScenario(
            firstToken = "t-exp",
            firstExpiresIn = 10,
            secondStatus = 500
        )
        stubUserExists(realm = "expired-refresh-realm", token = "t-exp")
        client.userExists("expired-refresh-realm", "user@example.com")
        advanceSeconds(11)

        // act
        assertThatThrownBy { client.userExists("expired-refresh-realm", "user@example.com") }
            // assert
            .isInstanceOf(KeycloakAdminClientException::class.java)

        val cacheField = client.javaClass.getDeclaredField("cachedAccessToken").apply { isAccessible = true }
        assertThat(cacheField.get(client)).isNull()
    }

    @Test
    fun `obtainAccessToken throws when token endpoint returns empty access token`() {
        // arrange
        server.resetAll()
        server.stubFor(
            post(urlEqualTo("/realms/master/protocol/openid-connect/token"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .willReturn(okJson("""{"access_token":"","expires_in":300}"""))
        )

        // act
        assertThatThrownBy { client.userExists("empty-token-realm", "user@example.com") }
            // assert
            .isInstanceOfSatisfying(KeycloakAdminClientException::class.java) { e ->
                assertThat(e.statusCode).isNull()
                assertThat(e.message).contains("empty access token")
            }
    }

    private fun stubToken(token: String = "admin-token") {
        server.stubFor(
            post(urlEqualTo("/realms/master/protocol/openid-connect/token"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withRequestBody(containing("client_id=admin-cli"))
                .withRequestBody(containing("client_secret=s3cr3t"))
                .willReturn(okJson("""{"access_token":"$token","expires_in":300}"""))
        )
    }

    private fun stubToken(token: String = "admin-token", expiresIn: Long) {
        server.stubFor(
            post(urlEqualTo("/realms/master/protocol/openid-connect/token"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withRequestBody(containing("client_id=admin-cli"))
                .withRequestBody(containing("client_secret=s3cr3t"))
                .willReturn(okJson("""{"access_token":"$token","expires_in":$expiresIn}"""))
        )
    }

    private fun stubTokenScenario(
        firstToken: String,
        firstExpiresIn: Long,
        secondStatus: Int,
        secondToken: String = "second-token"
    ) {
        val scenario = "token-refresh-$firstToken"
        server.stubFor(
            post(urlEqualTo("/realms/master/protocol/openid-connect/token"))
                .inScenario(scenario)
                .whenScenarioStateIs(STARTED)
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .willReturn(okJson("""{"access_token":"$firstToken","expires_in":$firstExpiresIn}"""))
                .willSetStateTo("REFRESH")
        )
        val secondResponse = if (secondStatus == 200) {
            okJson("""{"access_token":"$secondToken","expires_in":300}""")
        } else {
            aResponse().withStatus(secondStatus)
        }
        server.stubFor(
            post(urlEqualTo("/realms/master/protocol/openid-connect/token"))
                .inScenario(scenario)
                .whenScenarioStateIs("REFRESH")
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .willReturn(secondResponse)
        )
    }

    private fun roleArrayJson(size: Int, prefix: String): String {
        val body = (0 until size).joinToString(",", prefix = "[", postfix = "]") { i ->
            """{"id":"$i","name":"$prefix-$i"}"""
        }
        return body
    }

    private fun stubUserExists(realm: String, token: String) {
        server.stubFor(
            get(urlPathEqualTo("/admin/realms/$realm/users"))
                .withQueryParam("email", equalTo("user@example.com"))
                .withQueryParam("exact", equalTo("true"))
                .withQueryParam("max", equalTo("1"))
                .withQueryParam("briefRepresentation", equalTo("true"))
                .withHeader("Authorization", equalTo("Bearer $token"))
                .willReturn(okJson("""[{"id":"user-id","email":"user@example.com"}]"""))
        )
    }

    private fun advanceSeconds(seconds: Long) {
        clock.currentInstant = clock.currentInstant.plusSeconds(seconds)
    }

    private class MutableClock(var currentInstant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = currentInstant
    }
}
