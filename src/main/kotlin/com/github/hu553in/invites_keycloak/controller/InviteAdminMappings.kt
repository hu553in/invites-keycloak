package com.github.hu553in.invites_keycloak.controller

import com.github.hu553in.invites_keycloak.config.props.InviteProps
import com.github.hu553in.invites_keycloak.entity.InviteEntity
import com.github.hu553in.invites_keycloak.exception.InvalidInviteReason
import com.github.hu553in.invites_keycloak.util.MessageCodes
import java.time.Instant
import java.util.*

object InviteAdminMappings {
    data class InviteView(
        val id: UUID?,
        val createdAt: Instant,
        val createdBy: String,
        val email: String,
        val realm: String,
        val expiresAt: Instant,
        val uses: Int,
        val maxUses: Int,
        val expired: Boolean,
        val overused: Boolean,
        val revoked: Boolean,
        val revokedAt: Instant?,
        val revokedBy: String?,
        val active: Boolean,
        val roles: Set<String>,
        val statusClass: String,
        val statusMessageKey: String,
    )

    fun InviteEntity.toView(now: Instant): InviteView {
        val expired = expiresAt.isBefore(now)
        val overused = uses >= maxUses
        val active = !revoked && !expired && !overused
        val (statusMessageKey, statusClass) = when {
            revoked -> MessageCodes.InviteStatus.REVOKED to InvalidInviteReason.REVOKED.key
            expired -> MessageCodes.InviteStatus.EXPIRED to InvalidInviteReason.EXPIRED.key
            overused -> MessageCodes.InviteStatus.OVERUSED to InvalidInviteReason.OVERUSED.key
            else -> MessageCodes.InviteStatus.ACTIVE to "active"
        }

        return InviteView(
            id = id,
            createdAt = createdAt,
            createdBy = createdBy,
            email = email,
            realm = realm,
            expiresAt = expiresAt,
            uses = uses,
            maxUses = maxUses,
            expired = expired,
            overused = overused,
            revoked = revoked,
            revokedAt = revokedAt,
            revokedBy = revokedBy,
            active = active,
            roles = roles,
            statusClass = statusClass,
            statusMessageKey = statusMessageKey,
        )
    }

    fun buildInviteLink(inviteProps: InviteProps, realm: String, rawToken: String): String {
        val normalizedBase = inviteProps.publicBaseUrl.trimEnd('/')
        return "$normalizedBase/invite/$realm/$rawToken"
    }
}
