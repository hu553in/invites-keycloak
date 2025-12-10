package com.github.hu553in.invites_keycloak.config.logging

import com.github.hu553in.invites_keycloak.util.NANOS_PER_MILLI
import com.github.hu553in.invites_keycloak.util.REQUEST_DURATION_MS_KEY
import com.github.hu553in.invites_keycloak.util.REQUEST_METHOD_KEY
import com.github.hu553in.invites_keycloak.util.REQUEST_PATH_KEY
import com.github.hu553in.invites_keycloak.util.REQUEST_STATUS_KEY
import com.github.hu553in.invites_keycloak.util.logger
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping

private const val ACCESS_LOG_FILTER_ORDER = Ordered.LOWEST_PRECEDENCE - 10

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
        try {
            filterChain.doFilter(request, response)
        } finally {
            val path = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String
                ?: request.requestURI
            val durationMs = (System.nanoTime() - start) / NANOS_PER_MILLI
            log.atInfo()
                .addKeyValue(REQUEST_METHOD_KEY) { request.method }
                .addKeyValue(REQUEST_PATH_KEY) { path }
                .addKeyValue(REQUEST_STATUS_KEY) { response.status }
                .addKeyValue(REQUEST_DURATION_MS_KEY) { durationMs }
                .log { "HTTP request completed" }
        }
    }
}
