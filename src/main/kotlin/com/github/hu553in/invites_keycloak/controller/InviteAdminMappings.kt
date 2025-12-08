package com.github.hu553in.invites_keycloak.controller

import com.github.hu553in.invites_keycloak.config.props.InviteProps
import com.github.hu553in.invites_keycloak.entity.InviteEntity
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
        val usedUp: Boolean,
        val revoked: Boolean,
        val revokedAt: Instant?,
        val revokedBy: String?,
        val active: Boolean,
        val roles: Set<String>,
        val statusClass: String,
        val statusLabel: String
    )

    fun InviteEntity.toView(now: Instant): InviteView {
        val expired = expiresAt.isBefore(now)
        val usedUp = uses >= maxUses
        val active = !revoked && !expired && !usedUp
        val (statusLabel, statusClass) = when {
            revoked -> "Revoked" to "revoked"
            expired -> "Expired" to "expired"
            usedUp -> "Used-up" to "used-up"
            else -> "Active" to "active"
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
            usedUp = usedUp,
            revoked = revoked,
            revokedAt = revokedAt,
            revokedBy = revokedBy,
            active = active,
            roles = roles,
            statusClass = statusClass,
            statusLabel = statusLabel
        )
    }

    fun buildInviteLink(inviteProps: InviteProps, realm: String, rawToken: String): String {
        val normalizedBase = inviteProps.publicBaseUrl.trimEnd('/')
        return "$normalizedBase/invite/$realm/$rawToken"
    }
}
