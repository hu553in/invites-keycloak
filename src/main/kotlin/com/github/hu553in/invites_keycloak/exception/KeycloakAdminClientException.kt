package com.github.hu553in.invites_keycloak.exception

import org.springframework.http.HttpStatusCode

class KeycloakAdminClientException : RuntimeException {

    val statusCode: HttpStatusCode?

    constructor(message: String, cause: Throwable? = null) : super(message, cause) {
        statusCode = null
    }

    constructor(message: String, status: HttpStatusCode, cause: Throwable? = null) :
        super("$message (status: ${status.value()})", cause) {
        statusCode = status
    }
}
