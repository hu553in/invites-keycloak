package com.github.hu553in.invites_keycloak.controller

import com.github.hu553in.invites_keycloak.config.props.InviteProps
import com.github.hu553in.invites_keycloak.entity.InviteEntity
import java.time.Instant
import java.util.*

object InviteAdminMappings {
    data class InviteView(
        val id: UUID?,
        val createdAt: Instant,
        val email: String,
        val realm: String,
        val expiresAt: Instant,
        val uses: Int,
        val maxUses: Int,
        val revoked: Boolean,
        val roles: Set<String>,
        val statusClass: String,
        val statusLabel: String
    )

    fun InviteEntity.toView(now: Instant): InviteView {
        val expired = expiresAt.isBefore(now)
        val usedUp = uses >= maxUses
        val (statusLabel, statusClass) = when {
            revoked -> "Revoked" to "revoked"
            expired -> "Expired" to "expired"
            usedUp -> "Used-up" to "used-up"
            else -> "Active" to "active"
        }

        return InviteView(
            id = id,
            createdAt = createdAt,
            email = email,
            realm = realm,
            expiresAt = expiresAt,
            uses = uses,
            maxUses = maxUses,
            revoked = revoked,
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
