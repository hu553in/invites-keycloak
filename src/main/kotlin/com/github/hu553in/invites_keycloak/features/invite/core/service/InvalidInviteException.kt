package com.github.hu553in.invites_keycloak.features.invite.core.service

class InvalidInviteException(
    message: String = "Invite is invalid, expired, revoked, or overused"
) : RuntimeException(message)
