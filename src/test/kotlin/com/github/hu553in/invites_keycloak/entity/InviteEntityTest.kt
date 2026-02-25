package com.github.hu553in.invites_keycloak.entity

import com.github.hu553in.invites_keycloak.exception.InvalidInviteException
import com.github.hu553in.invites_keycloak.exception.InvalidInviteReason
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

class InviteEntityTest {

    @Test
    fun `incrementUses increments until max uses`() {
        // arrange
        val invite = invite(uses = 0, maxUses = 2)

        // act
        invite.incrementUses()
        invite.incrementUses()

        // assert
        assertThat(invite.uses).isEqualTo(2)
    }

    @Test
    fun `incrementUses throws overused invalid invite exception when exceeding max uses`() {
        // arrange
        val invite = invite(uses = 1, maxUses = 1)

        // act
        val exception = catchThrowableOfType(
            InvalidInviteException::class.java,
        ) { invite.incrementUses() }

        // assert
        assertThat(exception.reason).isEqualTo(InvalidInviteReason.OVERUSED)
    }

    private fun invite(uses: Int, maxUses: Int): InviteEntity {
        val now = Instant.parse("2025-01-01T00:00:00Z")
        return InviteEntity(
            id = UUID.randomUUID(),
            realm = "master",
            tokenHash = "token-hash",
            salt = "salt",
            email = "user@example.com",
            createdBy = "creator",
            createdAt = now,
            expiresAt = now.plusSeconds(3600),
            maxUses = maxUses,
            uses = uses,
            roles = setOf("user")
        )
    }
}
