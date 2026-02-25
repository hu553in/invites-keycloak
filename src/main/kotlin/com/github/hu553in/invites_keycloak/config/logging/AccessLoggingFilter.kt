package com.github.hu553in.invites_keycloak.config.logging

import com.github.hu553in.invites_keycloak.util.NANOS_PER_MILLI
import com.github.hu553in.invites_keycloak.util.REQUEST_DURATION_MS_KEY
import com.github.hu553in.invites_keycloak.util.REQUEST_METHOD_KEY
import com.github.hu553in.invites_keycloak.util.REQUEST_PATH_KEY
import com.github.hu553in.invites_keycloak.util.REQUEST_STATUS_KEY
import com.github.hu553in.invites_keycloak.util.eventForAppError
import com.github.hu553in.invites_keycloak.util.logger
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.spi.LoggingEventBuilder
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping

private const val ACCESS_LOG_FILTER_ORDER = Ordered.LOWEST_PRECEDENCE - 10
private const val MAX_5XX_HTTP_STATUS = 599

@Component
@ConditionalOnProperty(
    prefix = "access-logging",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false
)
@Order(ACCESS_LOG_FILTER_ORDER)
class AccessLoggingFilter : OncePerRequestFilter() {

    private val log by logger()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val start = System.nanoTime()
        var failure: Throwable? = null
        try {
            filterChain.doFilter(request, response)
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Throwable
        ) {
            failure = e
            throw e
        } finally {
            val path = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String
                ?: request.requestURI
            val durationMs = (System.nanoTime() - start) / NANOS_PER_MILLI
            val status = inferredStatus(response, failure)
            val event = accessEvent(status, failure)
            event
                .addKeyValue(REQUEST_METHOD_KEY) { request.method }
                .addKeyValue(REQUEST_PATH_KEY) { path }
                .addKeyValue(REQUEST_STATUS_KEY) { status }
                .addKeyValue(REQUEST_DURATION_MS_KEY) { durationMs }
                .log { accessMessage(status, failure) }
        }
    }

    private fun inferredStatus(response: HttpServletResponse, failure: Throwable?): Int {
        if (failure == null) {
            return response.status
        }
        return response.status.takeIf { it in HttpStatus.BAD_REQUEST.value()..MAX_5XX_HTTP_STATUS }
            ?: HttpStatus.INTERNAL_SERVER_ERROR.value()
    }

    private fun accessEvent(status: Int, failure: Throwable?): LoggingEventBuilder {
        val builder = when {
            status >= HttpStatus.INTERNAL_SERVER_ERROR.value() -> log.atError()
            status >= HttpStatus.BAD_REQUEST.value() -> log.atWarn()
            failure != null -> log.eventForAppError(failure)
            else -> log.atInfo()
        }
        return if (failure != null) {
            builder.setCause(failure)
        } else {
            builder
        }
    }

    private fun accessMessage(status: Int, failure: Throwable?): String {
        return when {
            failure != null -> "HTTP request failed"
            status >= HttpStatus.INTERNAL_SERVER_ERROR.value() -> "HTTP request completed with server error"
            status >= HttpStatus.BAD_REQUEST.value() -> "HTTP request completed with client error"
            else -> "HTTP request completed"
        }
    }
}
