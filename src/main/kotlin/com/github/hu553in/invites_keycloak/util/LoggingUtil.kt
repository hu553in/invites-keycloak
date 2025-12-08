package com.github.hu553in.invites_keycloak.util

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.jwt.Jwt

const val CURRENT_USER_ID_KEY = "current_user.id"
const val CURRENT_USER_SUBJECT_KEY = "current_user.subject"

private const val ANONYMOUS_USER_ID = "anonymousUser"
const val SYSTEM_USER_ID = "system"

fun <R : Any> R.logger(): Lazy<Logger> {
    return lazy { LoggerFactory.getLogger(this.javaClass) }
}

fun Authentication?.userIdOrSystem(): String {
    val userId = (this?.name ?: "").trim()
    if (userId.isNotBlank() && !userId.equals(ANONYMOUS_USER_ID, ignoreCase = true)) {
        return userId
    }
    return SYSTEM_USER_ID
}

fun Authentication?.subjectOrNull(): String? {
    val principal = this?.principal
    return when (principal) {
        is OidcUser -> principal.subject
        is Jwt -> principal.subject
        is OAuth2AuthenticatedPrincipal -> principal.getAttribute("sub")
        else -> null
    }
}
