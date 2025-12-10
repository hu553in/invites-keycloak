package com.github.hu553in.invites_keycloak.exception

class InvalidInviteException(
    message: String = "Invite is invalid, expired, revoked, or overused",
    cause: Throwable? = null
) : RuntimeException(message, cause)
