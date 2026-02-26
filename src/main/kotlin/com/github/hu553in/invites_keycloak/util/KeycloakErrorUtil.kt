package com.github.hu553in.invites_keycloak.util

import com.github.hu553in.invites_keycloak.exception.KeycloakAdminClientException
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.util.*

fun extractKeycloakException(error: Throwable): KeycloakAdminClientException? =
    error.causeSequence().filterIsInstance<KeycloakAdminClientException>().firstOrNull()

fun keycloakStatusFrom(error: Throwable): HttpStatusCode? {
    val keycloakEx = extractKeycloakException(error)
    val status = keycloakEx?.statusCode ?: keycloakEx?.causeSequence()
        ?.filterIsInstance<WebClientResponseException>()
        ?.firstOrNull()
        ?.statusCode
    val rootStatus = error.causeSequence()
        .filterIsInstance<WebClientResponseException>()
        .firstOrNull()
        ?.statusCode
    return status ?: rootStatus
}

fun isKeycloakMisconfiguration(statusCode: HttpStatusCode?): Boolean = statusCode?.let { status ->
    status.isSameCodeAs(HttpStatus.BAD_REQUEST) ||
        status.isSameCodeAs(HttpStatus.NOT_FOUND) ||
        status.isSameCodeAs(HttpStatus.UNPROCESSABLE_ENTITY) ||
        status.isSameCodeAs(HttpStatus.UNAUTHORIZED) ||
        status.isSameCodeAs(HttpStatus.FORBIDDEN)
} ?: false

private fun Throwable.causeSequence(): Sequence<Throwable> = sequence {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this@causeSequence
    while (current != null && visited.add(current)) {
        yield(current)
        current = current.cause
    }
}
