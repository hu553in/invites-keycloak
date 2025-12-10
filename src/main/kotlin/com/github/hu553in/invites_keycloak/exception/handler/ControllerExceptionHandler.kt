package com.github.hu553in.invites_keycloak.exception.handler

import com.github.hu553in.invites_keycloak.exception.InvalidInviteException
import com.github.hu553in.invites_keycloak.exception.InviteNotFoundException
import com.github.hu553in.invites_keycloak.exception.KeycloakAdminClientException
import com.github.hu553in.invites_keycloak.util.ErrorMessages
import com.github.hu553in.invites_keycloak.util.REQUEST_ROUTE_KEY
import com.github.hu553in.invites_keycloak.util.REQUEST_STATUS_KEY
import com.github.hu553in.invites_keycloak.util.eventForInviteError
import com.github.hu553in.invites_keycloak.util.logger
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.servlet.HandlerMapping

@ControllerAdvice(annotations = [Controller::class])
class ControllerExceptionHandler {

    private val log by logger()

    @ExceptionHandler(InvalidInviteException::class)
    fun handleInvalidInvite(
        e: InvalidInviteException,
        model: Model,
        req: HttpServletRequest,
        resp: HttpServletResponse
    ): String {
        log.atWarn()
            .setCause(e)
            .addKeyValue(REQUEST_ROUTE_KEY) { req.routePatternOrUnknown() }
            .addKeyValue(REQUEST_STATUS_KEY) { HttpStatus.UNAUTHORIZED.value() }
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
        req: HttpServletRequest,
        resp: HttpServletResponse
    ): String {
        val status = e.statusCode
        val responseStatus = if (status?.is4xxClientError == true) {
            status
        } else {
            HttpStatus.SERVICE_UNAVAILABLE
        }

        log.eventForInviteError(e, keycloakStatus = responseStatus, deduplicateKeycloak = true)
            .setCause(e)
            .addKeyValue(REQUEST_ROUTE_KEY) { req.routePatternOrUnknown() }
            .addKeyValue(REQUEST_STATUS_KEY) { responseStatus.value() }
            .log { "Keycloak admin client exception handled at controller layer" }

        if (responseStatus.is4xxClientError) {
            model.addAttributeIfAbsent("error_message", ErrorMessages.INVITE_CANNOT_BE_COMPLETED)
            model.addAttributeIfAbsent("error_details", ErrorMessages.INVITE_CANNOT_BE_COMPLETED_DETAILS)
        } else {
            model.addAttributeIfAbsent("error_message", ErrorMessages.SERVICE_TEMP_UNAVAILABLE)
            model.addAttributeIfAbsent("error_details", ErrorMessages.SERVICE_TEMP_UNAVAILABLE_DETAILS)
        }

        resp.status = responseStatus.value()
        return "generic_error"
    }

    @ExceptionHandler(InviteNotFoundException::class)
    fun handleInviteNotFound(
        e: InviteNotFoundException,
        model: Model,
        req: HttpServletRequest,
        resp: HttpServletResponse
    ): String {
        log.atWarn()
            .setCause(e)
            .addKeyValue(REQUEST_ROUTE_KEY) { req.routePatternOrUnknown() }
            .addKeyValue(REQUEST_STATUS_KEY) { HttpStatus.NOT_FOUND.value() }
            .log { "${InviteNotFoundException::class.simpleName} exception occurred" }
        if (!model.containsAttribute("error_message")) {
            model.addAttribute("error_message", e.message ?: "Invite is not found")
        }
        resp.status = HttpStatus.NOT_FOUND.value()
        return "generic_error"
    }

    @ExceptionHandler(Exception::class)
    fun handleUnknown(e: Exception, model: Model, req: HttpServletRequest, resp: HttpServletResponse): String {
        log.atError()
            .setCause(e)
            .addKeyValue(REQUEST_ROUTE_KEY) { req.routePatternOrUnknown() }
            .addKeyValue(REQUEST_STATUS_KEY) { HttpStatus.SERVICE_UNAVAILABLE.value() }
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

private fun HttpServletRequest.routePatternOrUnknown(): String {
    return this.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String ?: "unknown"
}
