package com.github.hu553in.invites_keycloak.exception

import org.springframework.http.HttpStatusCode

class KeycloakAdminClientException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {
    constructor(
        message: String,
        status: HttpStatusCode,
        cause: Throwable? = null
    ) : this("$message (status: ${status.value()})", cause)
}
