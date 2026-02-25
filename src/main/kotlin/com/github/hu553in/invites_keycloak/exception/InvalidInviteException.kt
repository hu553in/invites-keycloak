package com.github.hu553in.invites_keycloak.exception

enum class InvalidInviteReason(val key: String) {
    MALFORMED("malformed"),
    REVOKED("revoked"),
    EXPIRED("expired"),
    OVERUSED("overused")
}

class InvalidInviteException(
    cause: Throwable? = null,
    val reason: InvalidInviteReason
) : RuntimeException("Invite is invalid: ${reason.key}", cause)
