package com.github.hu553in.invites_keycloak.exception

import java.util.*

class InviteNotFoundException(inviteId: UUID? = null) :
    RuntimeException(
        if (inviteId != null) {
            "Invite $inviteId is not found"
        } else {
            "Invite is not found"
        },
    )
