package com.github.hu553in.invites_keycloak.shared.web

import com.github.hu553in.invites_keycloak.features.invite.core.service.InvalidInviteException
import com.github.hu553in.invites_keycloak.features.keycloak.core.service.KeycloakAdminClientException
import com.github.hu553in.invites_keycloak.shared.util.logger
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {

    private val log by logger()

    @ExceptionHandler(InvalidInviteException::class)
    fun handleInvalidInvite(e: InvalidInviteException): ProblemDetail {
        log.warn("${InvalidInviteException::class.simpleName} exception occurred", e)
        return ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED)
    }

    @ExceptionHandler(KeycloakAdminClientException::class)
    fun handleKeycloakAdminClient(e: KeycloakAdminClientException): ProblemDetail {
        log.error("${KeycloakAdminClientException::class.simpleName} exception occurred", e)
        return ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnknown(e: Exception): ProblemDetail {
        log.error("Unknown exception led to 500 response code", e)
        return ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    }
}
