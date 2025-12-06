package com.github.hu553in.invites_keycloak.client

import com.github.hu553in.invites_keycloak.config.props.KeycloakProps
import com.github.hu553in.invites_keycloak.exception.KeycloakAdminClientException
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.noContent
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.put
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.web.reactive.function.client.WebClient
import java.time.Clock
import java.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpKeycloakAdminClientTest {

    private lateinit var server: WireMockServer
    private lateinit var client: KeycloakAdminClient

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

        client = HttpKeycloakAdminClient(
            keycloakProps = KeycloakProps(
                url = server.baseUrl(),
                realm = "master",
                clientId = "admin-cli",
                clientSecret = "s3cr3t",
                requiredRole = "invite-admin",
                maxAttempts = 3,
                minBackoff = Duration.ofSeconds(2)
            ),
            clock = Clock.systemUTC(),
            webClientBuilder = WebClient.builder()
        )
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

    private fun roleArrayJson(size: Int, prefix: String): String {
        val body = (0 until size).joinToString(",", prefix = "[", postfix = "]") { i ->
            """{"id":"$i","name":"$prefix-$i"}"""
        }
        return body
    }
}
