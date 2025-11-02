package com.github.hu553in.invites_keycloak.exception

import java.util.*

class InviteNotFoundException(inviteId: UUID) : RuntimeException("Invite $inviteId is not found")
