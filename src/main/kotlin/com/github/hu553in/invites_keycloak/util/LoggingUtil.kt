package com.github.hu553in.invites_keycloak.util

import com.github.hu553in.invites_keycloak.exception.ActiveInviteExistsException
import com.github.hu553in.invites_keycloak.exception.InvalidInviteException
import com.github.hu553in.invites_keycloak.exception.InviteNotFoundException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.spi.LoggingEventBuilder
import org.springframework.http.HttpStatusCode
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.jwt.Jwt
import java.util.*

const val NANOS_PER_MILLI = 1_000_000L

const val CURRENT_USER_ID_KEY = "current_user.id"
const val CURRENT_USER_SUBJECT_KEY = "current_user.subject"

const val REQUEST_METHOD_KEY = "http.method"
const val REQUEST_PATH_KEY = "http.path"
const val REQUEST_STATUS_KEY = "http.status"
const val REQUEST_DURATION_MS_KEY = "http.duration_ms"

const val INVITE_ID_KEY = "invite.id"
const val INVITE_REALM_KEY = "invite.realm"
const val INVITE_EMAIL_KEY = "invite.email"

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

/**
 * Executes the given [block] with the provided key/value pairs added to MDC for the duration of the call.
 * Existing values are restored afterwards to avoid leaking context across requests/threads.
 */
fun <T> withMdc(vararg entries: Pair<String, String?>, block: () -> T): T {
    if (entries.isEmpty()) {
        return block()
    }

    val previous = mutableMapOf<String, String?>()
    entries.forEach { (key, value) ->
        val trimmed = value?.trim()
        previous[key] = MDC.get(key)
        if (trimmed.isNullOrEmpty()) {
            MDC.remove(key)
        } else {
            MDC.put(key, trimmed)
        }
    }

    return try {
        block()
    } finally {
        previous.forEach { (key, value) ->
            if (value == null) {
                MDC.remove(key)
            } else {
                MDC.put(key, value)
            }
        }
    }
}

fun <T> withAuthDataInMdc(id: String, sub: String? = null, block: () -> T): T {
    return withMdc(
        CURRENT_USER_ID_KEY to id,
        CURRENT_USER_SUBJECT_KEY to sub
    ) {
        block()
    }
}

fun <T> withInviteContextInMdc(inviteId: UUID?, realm: String?, email: String?, block: () -> T): T {
    return withMdc(
        INVITE_ID_KEY to inviteId?.toString(),
        INVITE_REALM_KEY to realm,
        INVITE_EMAIL_KEY to email?.let { maskSensitive(it) }
    ) {
        block()
    }
}

fun Throwable.isClientSideInviteFailure(): Boolean {
    return this is IllegalArgumentException ||
        this is IllegalStateException ||
        this is ActiveInviteExistsException ||
        this is InviteNotFoundException ||
        this is InvalidInviteException
}

fun Logger.eventForInviteError(
    error: Throwable,
    keycloakStatus: HttpStatusCode? = null,
    deduplicateKeycloak: Boolean = false
): LoggingEventBuilder {
    if (deduplicateKeycloak && extractKeycloakException(error) != null) {
        return this.atDebug()
    }
    val status = keycloakStatus ?: keycloakStatusFrom(error)
    return when {
        status != null && isKeycloakMisconfiguration(status) -> this.atError()
        status?.is4xxClientError == true -> this.atWarn()
        error.isClientSideInviteFailure() -> this.atWarn()
        else -> this.atError()
    }
}
