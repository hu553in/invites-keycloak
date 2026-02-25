package com.github.hu553in.invites_keycloak.util

import com.github.hu553in.invites_keycloak.exception.KeycloakAdminClientException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.nio.charset.StandardCharsets

class KeycloakErrorUtilTest {

    @Test
    fun `extractKeycloakException finds nested exception in cause chain`() {
        // arrange
        val keycloakError = KeycloakAdminClientException("kc", HttpStatus.BAD_GATEWAY)
        val wrapped = RuntimeException("outer", IllegalStateException("inner", keycloakError))

        // act
        val extracted = extractKeycloakException(wrapped)

        // assert
        assertThat(extracted).isSameAs(keycloakError)
    }

    @Test
    fun `keycloakStatusFrom resolves nested Keycloak status from cause chain`() {
        // arrange
        val keycloakError = KeycloakAdminClientException("kc", HttpStatus.SERVICE_UNAVAILABLE)
        val wrapped = RuntimeException("outer", IllegalStateException("inner", keycloakError))

        // act
        val status = keycloakStatusFrom(wrapped)

        // assert
        assertThat(status?.value()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value())
    }

    @Test
    fun `keycloakStatusFrom resolves nested webclient response status when keycloak exception has no status`() {
        // arrange
        val webClientError = WebClientResponseException.create(
            HttpStatus.TOO_MANY_REQUESTS.value(),
            "Too Many Requests",
            HttpHeaders.EMPTY,
            ByteArray(0),
            StandardCharsets.UTF_8
        )
        val keycloakError = KeycloakAdminClientException("kc", webClientError)
        val wrapped = RuntimeException("outer", IllegalStateException("inner", keycloakError))

        // act
        val status = keycloakStatusFrom(wrapped)

        // assert
        assertThat(status?.value()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value())
    }

    @Test
    fun `cause chain traversal handles cycles without infinite loop`() {
        // arrange
        val a = RuntimeException("a")
        val b = IllegalStateException("b", a)
        a.initCause(b)

        // act
        val extracted = extractKeycloakException(a)
        val status = keycloakStatusFrom(a)

        // assert
        assertThat(extracted).isNull()
        assertThat(status).isNull()
    }
}
