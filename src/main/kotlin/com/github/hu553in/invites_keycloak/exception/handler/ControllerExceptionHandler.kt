package com.github.hu553in.invites_keycloak.exception.handler

import com.github.hu553in.invites_keycloak.exception.InvalidInviteException
import com.github.hu553in.invites_keycloak.exception.InviteNotFoundException
import com.github.hu553in.invites_keycloak.exception.KeycloakAdminClientException
import com.github.hu553in.invites_keycloak.util.ErrorMessages
import com.github.hu553in.invites_keycloak.util.logger
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
        log.atWarn()
            .setCause(e)
            .log { "${InvalidInviteException::class.simpleName} exception occurred" }
        if (!model.containsAttribute("error_message")) {
            model.addAttribute("error_message", "Invite is invalid")
        }
        resp.status = HttpStatus.UNAUTHORIZED.value()
        return "generic_error"
    }

    @ExceptionHandler(KeycloakAdminClientException::class)
    fun handleKeycloakAdminClient(
        e: KeycloakAdminClientException,
        model: Model,
        resp: HttpServletResponse
    ): String {
        log.atError()
            .setCause(e)
            .log { "${KeycloakAdminClientException::class.simpleName} exception occurred" }
        model.addAttributeIfAbsent("error_message", ErrorMessages.SERVICE_TEMP_UNAVAILABLE)
        model.addAttributeIfAbsent("error_details", ErrorMessages.SERVICE_TEMP_UNAVAILABLE_DETAILS)
        resp.status = HttpStatus.SERVICE_UNAVAILABLE.value()
        return "generic_error"
    }

    @ExceptionHandler(InviteNotFoundException::class)
    fun handleInviteNotFound(
        e: InviteNotFoundException,
        model: Model,
        resp: HttpServletResponse
    ): String {
        log.atWarn()
            .setCause(e)
            .log { "${InviteNotFoundException::class.simpleName} exception occurred" }
        if (!model.containsAttribute("error_message")) {
            model.addAttribute("error_message", e.message ?: "Invite is not found")
        }
        resp.status = HttpStatus.NOT_FOUND.value()
        return "generic_error"
    }

    @ExceptionHandler(Exception::class)
    fun handleUnknown(e: Exception, model: Model, resp: HttpServletResponse): String {
        log.atError()
            .setCause(e)
            .log { "Unknown exception handled at controller layer" }
        model.addAttributeIfAbsent("error_message", ErrorMessages.SERVICE_TEMP_UNAVAILABLE)
        model.addAttributeIfAbsent("error_details", ErrorMessages.SERVICE_TEMP_UNAVAILABLE_DETAILS)
        resp.status = HttpStatus.SERVICE_UNAVAILABLE.value()
        return "generic_error"
    }
}

private fun Model.addAttributeIfAbsent(name: String, value: Any) {
    if (!this.containsAttribute(name)) {
        this.addAttribute(name, value)
    }
}
