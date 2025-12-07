package com.github.hu553in.invites_keycloak.config.logging

import com.github.hu553in.invites_keycloak.util.CURRENT_USER_ID_KEY
import com.github.hu553in.invites_keycloak.util.CURRENT_USER_SUBJECT_KEY
import com.github.hu553in.invites_keycloak.util.subjectOrNull
import com.github.hu553in.invites_keycloak.util.userIdOrSystem
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Adds the current authenticated user id to MDC so all logs within the request include it.
 */
@Component
class CurrentUserLoggingFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val previousId = MDC.get(CURRENT_USER_ID_KEY)
        val previousSub = MDC.get(CURRENT_USER_SUBJECT_KEY)

        val authentication = SecurityContextHolder.getContext().authentication
        val id = authentication.userIdOrSystem()
        val sub = authentication.subjectOrNull()

        MDC.put(CURRENT_USER_ID_KEY, id)
        if (sub != null) {
            MDC.put(CURRENT_USER_SUBJECT_KEY, sub)
        }
        try {
            filterChain.doFilter(request, response)
        } finally {
            if (previousId != null) {
                MDC.put(CURRENT_USER_ID_KEY, previousId)
            } else {
                MDC.remove(CURRENT_USER_ID_KEY)
            }
            if (previousSub != null) {
                MDC.put(CURRENT_USER_SUBJECT_KEY, previousSub)
            } else {
                MDC.remove(CURRENT_USER_SUBJECT_KEY)
            }
        }
    }
}
