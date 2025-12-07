package com.github.hu553in.invites_keycloak.controller

import com.github.hu553in.invites_keycloak.controller.InviteAdminMappings.toView
import com.github.hu553in.invites_keycloak.entity.InviteEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

class InviteAdminMappingsTest {

    @Test
    fun `marks invite as used-up when uses reach max`() {
        val now = Instant.parse("2025-01-01T00:00:00Z")
        val view = InviteEntity(
            id = UUID.randomUUID(),
            realm = "master",
            tokenHash = "hash",
            salt = "salt",
            email = "user@example.com",
            createdBy = "creator",
            createdAt = now,
            expiresAt = now.plusSeconds(3600),
            maxUses = 1,
            uses = 1,
            roles = setOf("role")
        ).toView(now)

        assertThat(view.statusClass).isEqualTo("used-up")
        assertThat(view.statusLabel).isEqualTo("Used-up")
    }
}
