package com.github.hu553in.invites_keycloak.features.invite.core.repo

import com.github.hu553in.invites_keycloak.features.invite.core.model.InviteEntity
import com.github.hu553in.invites_keycloak.shared.config.TestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestConstructor
import java.time.Instant
import java.time.temporal.ChronoUnit

@EnableAutoConfiguration
@EntityScan(basePackageClasses = [InviteEntity::class])
@Import(TestcontainersConfig::class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class InviteRepositoryTestConfig

@DataJpaTest
@ContextConfiguration(classes = [InviteRepositoryTestConfig::class])
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class InviteRepositoryTest(
    private val inviteRepository: InviteRepository
) {

    @Test
    fun `findValidByRealmAndTokenHash returns active invite`() {
        // arrange
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val expiresAt = now.plusSeconds(3600)

        val invite = InviteEntity(
            realm = "master",
            tokenHash = "token-hash",
            salt = "salt-value",
            email = "user@example.com",
            createdBy = "creator",
            createdAt = now,
            expiresAt = expiresAt,
            maxUses = 2,
            uses = 0,
            revoked = false,
            roles = setOf("realm-admin")
        )

        val saved = inviteRepository.saveAndFlush(invite)

        // act
        val found = inviteRepository.findValidByRealmAndTokenHash("master", "token-hash", now)

        // assert
        assertThat(found).isPresent()
        assertThat(found.get().id).isEqualTo(saved.id)
        assertThat(found.get().roles).containsExactlyInAnyOrder("realm-admin")
        assertThat(found.get().expiresAt).isAfter(now)
    }

    @Test
    fun `findValidByRealmAndTokenHash does not return revoked invite`() {
        // arrange
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val expiresAt = now.plusSeconds(3600)

        val invite = InviteEntity(
            realm = "master",
            tokenHash = "token-hash",
            salt = "salt-value",
            email = "user@example.com",
            createdBy = "creator",
            createdAt = now,
            expiresAt = expiresAt,
            maxUses = 2,
            uses = 0,
            revoked = true,
            roles = setOf("realm-admin")
        )

        inviteRepository.saveAndFlush(invite)

        // act
        val found = inviteRepository.findValidByRealmAndTokenHash("master", "token-hash", now)

        // assert
        assertThat(found).isNotPresent()
    }

    @Test
    fun `findValidByRealmAndTokenHash does not return overused invite`() {
        // arrange
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val expiresAt = now.plusSeconds(3600)

        val invite = InviteEntity(
            realm = "master",
            tokenHash = "token-hash",
            salt = "salt-value",
            email = "user@example.com",
            createdBy = "creator",
            createdAt = now,
            expiresAt = expiresAt,
            maxUses = 2,
            uses = 2,
            revoked = false,
            roles = setOf("realm-admin")
        )

        inviteRepository.saveAndFlush(invite)

        // act
        val found = inviteRepository.findValidByRealmAndTokenHash("master", "token-hash", now)

        // assert
        assertThat(found).isNotPresent()
    }

    @Test
    fun `findByRealmAndEmail returns invite`() {
        // arrange
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val expiresAt = now.plusSeconds(3600)

        val invite = InviteEntity(
            realm = "master",
            tokenHash = "token-hash",
            salt = "salt-value",
            email = "user@example.com",
            createdBy = "creator",
            createdAt = now,
            expiresAt = expiresAt,
            maxUses = 2,
            uses = 0,
            revoked = false,
            roles = setOf("realm-admin")
        )

        val saved = inviteRepository.saveAndFlush(invite)

        // act
        val found = inviteRepository.findByRealmAndEmail("master", "user@example.com")

        // assert
        assertThat(found).isPresent()
        assertThat(found.get().id).isEqualTo(saved.id)
        assertThat(found.get().roles).containsExactlyInAnyOrder("realm-admin")
        assertThat(found.get().expiresAt).isAfter(now)
    }
}
