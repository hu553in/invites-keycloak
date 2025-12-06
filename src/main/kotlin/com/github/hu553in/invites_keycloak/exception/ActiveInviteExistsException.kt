package com.github.hu553in.invites_keycloak.exception

import com.github.hu553in.invites_keycloak.util.maskSensitive

class ActiveInviteExistsException(
    val realm: String,
    val email: String
) : RuntimeException("Active invite already exists for ${maskSensitive(email)} in realm $realm")
