package com.github.hu553in.invites_keycloak.exception.handler

import com.github.hu553in.invites_keycloak.util.logger
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

@RestControllerAdvice(annotations = [RestController::class])
class RestControllerExceptionHandler : ResponseEntityExceptionHandler() {

    private val log by logger()

    @ExceptionHandler(Exception::class)
    fun handleUnknown(e: Exception): ProblemDetail {
        log.atError()
            .setCause(e)
            .log { "Unknown exception led to 500 response code" }
        return ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    }
}
