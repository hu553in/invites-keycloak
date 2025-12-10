package com.github.hu553in.invites_keycloak.util

import com.github.hu553in.invites_keycloak.exception.KeycloakAdminClientException
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.web.reactive.function.client.WebClientResponseException

fun extractKeycloakException(error: Throwable): KeycloakAdminClientException? {
    return when (error) {
        is KeycloakAdminClientException -> error
        else -> error.cause as? KeycloakAdminClientException
    }
}

fun keycloakStatusFrom(error: Throwable): HttpStatusCode? {
    val keycloakEx = extractKeycloakException(error)
    val status = keycloakEx?.statusCode ?: (keycloakEx?.cause as? WebClientResponseException)?.statusCode
    val root = keycloakEx?.cause ?: error.cause ?: error
    val rootStatus = (root as? WebClientResponseException)?.statusCode
    return status ?: rootStatus
}

fun isKeycloakMisconfiguration(statusCode: HttpStatusCode?): Boolean {
    return statusCode?.let { status ->
        status.isSameCodeAs(HttpStatus.BAD_REQUEST) ||
            status.isSameCodeAs(HttpStatus.NOT_FOUND) ||
            status.isSameCodeAs(HttpStatus.UNPROCESSABLE_ENTITY) ||
            status.isSameCodeAs(HttpStatus.UNAUTHORIZED) ||
            status.isSameCodeAs(HttpStatus.FORBIDDEN)
    } ?: false
}
