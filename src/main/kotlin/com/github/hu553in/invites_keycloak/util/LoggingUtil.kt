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
const val REQUEST_ROUTE_KEY = "http.route"
const val REQUEST_STATUS_KEY = "http.status"
const val REQUEST_URI_KEY = "http.uri"
const val REQUEST_REASON_KEY = "http.reason"
const val REQUEST_DURATION_MS_KEY = "http.duration_ms"
const val KEYCLOAK_REALM_KEY = "keycloak.realm"
const val KEYCLOAK_OPERATION_KEY = "keycloak.operation"

const val RETRY_ATTEMPT_KEY = "retry.attempt"
const val RETRY_MAX_ATTEMPTS_KEY = "retry.max_attempts"

const val INVITE_ID_KEY = "invite.id"
const val INVITE_EMAIL_KEY = "invite.email"
const val INVITE_INVALID_REASON_KEY = "invite.invalid_reason"
const val INVITE_COUNT_KEY = "invite.count"
const val INVITE_REVOKED_EXPIRED_COUNT_KEY = "invite.revoked_expired_count"
const val INVITE_REVOKED_OVERUSED_COUNT_KEY = "invite.revoked_overused_count"
const val INVITE_EXPIRES_AT_KEY = "invite.expires_at"
const val INVITE_MAX_USES_KEY = "invite.max_uses"
const val INVITE_USES_KEY = "invite.uses"
const val INVITE_ROLES_KEY = "invite.roles"
const val INVITE_CREATED_BY_KEY = "invite.created_by"
const val INVITE_DELETED_BY_KEY = "invite.deleted_by"
const val INVITE_REVOKED_BY_KEY = "invite.revoked_by"
const val INVITE_RESENT_BY_KEY = "invite.resent_by"
const val INVITE_PREVIOUS_ID_KEY = "invite.id.previous"
const val INVITE_CREATED_ID_KEY = "invite.id.created"
const val INVITE_TOKEN_LENGTH_KEY = "invite.token_length"
const val INVITE_EXPIRY_MINUTES_KEY = "invite.expiry_minutes"
const val INVITE_FLOW_SHOULD_REVOKE_KEY = "invite.flow.should_revoke"

const val USER_ID_KEY = "user.id"
const val USERNAME_KEY = "user.username"
const val USER_EXISTS_KEY = "user.exists"

const val ROLE_KEY = "role"
const val ROLE_COUNT_KEY = "role.count"

const val CONFIGURED_BYTES_KEY = "configured.bytes"
const val REQUIRED_MIN_BYTES_KEY = "required_min_bytes"
const val MAC_ALGORITHM_KEY = "mac.algorithm"
const val TOKEN_BYTES_KEY = "token.bytes"
const val SALT_BYTES_KEY = "salt.bytes"
const val HASH_LENGTH_KEY = "hash.length"

const val ARG_KEY = "arg.name"
const val ARG_EXPECTED_BYTES_KEY = "arg.expected_bytes"
const val ARG_VALUE_LENGTH_KEY = "arg.value_length"
const val ACTIONS_KEY = "actions"
const val ERROR_COUNT_KEY = "error.count"
const val RETENTION_DAYS_KEY = "retention.days"
const val DELETED_COUNT_KEY = "deleted.count"
const val MAIL_STATUS_KEY = "mail.status"

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
    return when (val principal = this?.principal) {
        is OidcUser -> principal.subject
        is Jwt -> principal.subject
        is OAuth2AuthenticatedPrincipal -> principal.getAttribute("sub")
        else -> null
    }
}

/**
 * Executes the given [block] with the provided key/value pairs added to MDC for the duration of the call.
 * Existing values are restored afterward to avoid leaking context across requests/threads.
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
        KEYCLOAK_REALM_KEY to realm,
        INVITE_EMAIL_KEY to email?.let { maskSensitive(it) }
    ) {
        block()
    }
}

fun Throwable.isClientSideAppFailure(): Boolean {
    return this is IllegalArgumentException ||
        this is IllegalStateException ||
        this is ActiveInviteExistsException ||
        this is InviteNotFoundException ||
        this is InvalidInviteException
}

/**
 * Standardized log level chooser for application errors.
 * Use deduplication (`deduplicateKeycloak = true`) in upper layers when a Keycloak call already logged
 * the failure to avoid double warn/error entries; only add contextual keys at that layer.
 */
fun Logger.eventForAppError(
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
        error.isClientSideAppFailure() -> this.atWarn()
        else -> this.atError()
    }
}

/**
 * Convenience wrapper to avoid duplicate warn/error logs when a Keycloak call already logged the failure.
 * Use this in controller layers and other outer layers when handling KeycloakAdminClientException.
 */
fun Logger.dedupedEventForAppError(
    error: Throwable,
    keycloakStatus: HttpStatusCode? = null
): LoggingEventBuilder {
    return this.eventForAppError(error, keycloakStatus = keycloakStatus, deduplicateKeycloak = true)
}
