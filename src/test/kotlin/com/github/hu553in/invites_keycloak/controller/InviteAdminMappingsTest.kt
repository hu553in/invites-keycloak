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
        assertThat(view.usedUp).isTrue()
        assertThat(view.active).isFalse()
        assertThat(view.expired).isFalse()
    }

    @Test
    fun `marks invite as expired when past expiry`() {
        val now = Instant.parse("2025-01-02T00:00:00Z")
        val view = InviteEntity(
            id = UUID.randomUUID(),
            realm = "master",
            tokenHash = "hash",
            salt = "salt",
            email = "user@example.com",
            createdBy = "creator",
            createdAt = now.minusSeconds(3600),
            expiresAt = now.minusSeconds(10),
            maxUses = 1,
            uses = 0,
            roles = setOf("role")
        ).toView(now)

        assertThat(view.statusClass).isEqualTo("expired")
        assertThat(view.statusLabel).isEqualTo("Expired")
        assertThat(view.expired).isTrue()
        assertThat(view.active).isFalse()
        assertThat(view.usedUp).isFalse()
    }
}
