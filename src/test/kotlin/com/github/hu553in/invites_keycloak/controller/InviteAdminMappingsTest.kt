package com.github.hu553in.invites_keycloak.controller

import com.github.hu553in.invites_keycloak.controller.InviteAdminMappings.toView
import com.github.hu553in.invites_keycloak.entity.InviteEntity
import com.github.hu553in.invites_keycloak.exception.InvalidInviteReason
import com.github.hu553in.invites_keycloak.util.UiInviteStatuses
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

class InviteAdminMappingsTest {

    @Test
    fun `marks invite as active when not expired, overused, or revoked`() {
        // arrange
        val now = Instant.parse("2025-01-01T00:00:00Z")

        // act
        val view = InviteEntity(
            id = UUID.randomUUID(),
            realm = "master",
            tokenHash = "hash",
            salt = "salt",
            email = "user@example.com",
            createdBy = "creator",
            createdAt = now.minusSeconds(60),
            expiresAt = now.plusSeconds(3600),
            maxUses = 2,
            uses = 1,
            roles = setOf("role")
        ).toView(now)

        // assert
        assertThat(view.statusClass).isEqualTo("active")
        assertThat(view.statusLabel).isEqualTo(UiInviteStatuses.ACTIVE)
        assertThat(view.active).isTrue()
        assertThat(view.overused).isFalse()
        assertThat(view.expired).isFalse()
        assertThat(view.revoked).isFalse()
    }

    @Test
    fun `marks invite as overused when uses reach max`() {
        // arrange
        val now = Instant.parse("2025-01-01T00:00:00Z")

        // act
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

        // assert
        assertThat(view.statusClass).isEqualTo(InvalidInviteReason.OVERUSED.key)
        assertThat(view.statusLabel).isEqualTo(UiInviteStatuses.OVERUSED)
        assertThat(view.createdBy).isEqualTo("creator")
        assertThat(view.overused).isTrue()
        assertThat(view.active).isFalse()
        assertThat(view.expired).isFalse()
        assertThat(view.revokedAt).isNull()
        assertThat(view.revokedBy).isNull()
    }

    @Test
    fun `marks invite as expired when past expiry`() {
        // arrange
        val now = Instant.parse("2025-01-02T00:00:00Z")

        // act
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

        // assert
        assertThat(view.statusClass).isEqualTo(InvalidInviteReason.EXPIRED.key)
        assertThat(view.statusLabel).isEqualTo(UiInviteStatuses.EXPIRED)
        assertThat(view.createdBy).isEqualTo("creator")
        assertThat(view.expired).isTrue()
        assertThat(view.active).isFalse()
        assertThat(view.overused).isFalse()
        assertThat(view.revokedAt).isNull()
        assertThat(view.revokedBy).isNull()
    }

    @Test
    fun `sets revoked metadata when present`() {
        // arrange
        val now = Instant.parse("2025-01-03T00:00:00Z")
        val revokedAt = now.minusSeconds(30)

        // act
        val view = InviteEntity(
            id = UUID.randomUUID(),
            realm = "master",
            tokenHash = "hash",
            salt = "salt",
            email = "user@example.com",
            createdBy = "creator",
            createdAt = now.minusSeconds(3600),
            expiresAt = now.plusSeconds(3600),
            maxUses = 1,
            revoked = true,
            revokedAt = revokedAt,
            revokedBy = "revoker",
            roles = setOf("role")
        ).toView(now)

        // assert
        assertThat(view.statusClass).isEqualTo(InvalidInviteReason.REVOKED.key)
        assertThat(view.statusLabel).isEqualTo(UiInviteStatuses.REVOKED)
        assertThat(view.revokedAt).isEqualTo(revokedAt)
        assertThat(view.revokedBy).isEqualTo("revoker")
        assertThat(view.active).isFalse()
    }

    @Test
    fun `revoked status has priority over expired and overused flags`() {
        // arrange
        val now = Instant.parse("2025-01-03T00:00:00Z")

        // act
        val view = InviteEntity(
            id = UUID.randomUUID(),
            realm = "master",
            tokenHash = "hash",
            salt = "salt",
            email = "user@example.com",
            createdBy = "creator",
            createdAt = now.minusSeconds(3600),
            expiresAt = now.minusSeconds(1),
            maxUses = 1,
            uses = 1,
            revoked = true,
            revokedAt = now.minusSeconds(30),
            revokedBy = "revoker",
            roles = setOf("role")
        ).toView(now)

        // assert
        assertThat(view.statusClass).isEqualTo(InvalidInviteReason.REVOKED.key)
        assertThat(view.statusLabel).isEqualTo(UiInviteStatuses.REVOKED)
        assertThat(view.revoked).isTrue()
        assertThat(view.expired).isTrue()
        assertThat(view.overused).isTrue()
        assertThat(view.active).isFalse()
    }
}
