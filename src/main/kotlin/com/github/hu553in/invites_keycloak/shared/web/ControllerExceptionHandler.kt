package com.github.hu553in.invites_keycloak.shared.web

import com.github.hu553in.invites_keycloak.features.invite.core.service.InvalidInviteException
import com.github.hu553in.invites_keycloak.features.keycloak.core.service.KeycloakAdminClientException
import com.github.hu553in.invites_keycloak.shared.util.logger
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

@ControllerAdvice(annotations = [Controller::class])
class ControllerExceptionHandler {

    private val log by logger()

    @ExceptionHandler(InvalidInviteException::class)
    fun handleInvalidInvite(e: InvalidInviteException, model: Model, resp: HttpServletResponse): String {
        log.warn("${InvalidInviteException::class.simpleName} exception occurred", e)
        model.addAttribute("error_message", "Invite is invalid")
        resp.status = HttpStatus.UNAUTHORIZED.value()
        return "public/generic_error"
    }

    @ExceptionHandler(KeycloakAdminClientException::class)
    fun handleKeycloakAdminClient(
        e: KeycloakAdminClientException,
        model: Model,
        resp: HttpServletResponse
    ): String {
        log.error("${KeycloakAdminClientException::class.simpleName} exception occurred", e)
        model.addAttribute("error_message", "Service is not available")
        resp.status = HttpStatus.SERVICE_UNAVAILABLE.value()
        return "public/generic_error"
    }

    @ExceptionHandler(Exception::class)
    fun handleUnknown(e: Exception, model: Model, resp: HttpServletResponse): String {
        log.error("Unknown exception led to 500 response code", e)
        model.addAttribute("error_message", "Unknown error")
        resp.status = HttpStatus.INTERNAL_SERVER_ERROR.value()
        return "public/generic_error"
    }
}
