package com.github.hu553in.invites_keycloak.config.logging

import com.github.hu553in.invites_keycloak.util.subjectOrNull
import com.github.hu553in.invites_keycloak.util.userIdOrSystem
import com.github.hu553in.invites_keycloak.util.withAuthDataInMdc
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private const val CURRENT_USER_FILTER_ORDER = Ordered.LOWEST_PRECEDENCE - 20

/**
 * Adds the current authenticated user id to MDC so all logs within the request include it.
 */
@Component
@Order(CURRENT_USER_FILTER_ORDER)
class CurrentUserLoggingFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val authentication = SecurityContextHolder.getContext().authentication
        val id = authentication.userIdOrSystem()
        val sub = authentication.subjectOrNull()

        withAuthDataInMdc(id, sub) { filterChain.doFilter(request, response) }
    }
}
