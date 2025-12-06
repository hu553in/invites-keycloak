package com.github.hu553in.invites_keycloak.repo

import com.github.hu553in.invites_keycloak.config.TestcontainersConfig
import com.github.hu553in.invites_keycloak.entity.InviteEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestConstructor
import java.time.Clock
import java.time.temporal.ChronoUnit

@EnableAutoConfiguration
@EntityScan(basePackageClasses = [InviteEntity::class])
@Import(TestcontainersConfig::class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class InviteRepositoryTestConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

@DataJpaTest
@ContextConfiguration(classes = [InviteRepositoryTestConfig::class])
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class InviteRepositoryTest(
    private val inviteRepository: InviteRepository,
    private val clock: Clock
) {

    @Test
    fun `findValidByRealmAndTokenHash returns active invite`() {
        // arrange
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
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
            roles = setOf("realm-admin")
        )

        val saved = inviteRepository.saveAndFlush(invite)

        // act
        val found = inviteRepository.findValidByRealmAndTokenHash("master", "token-hash", clock.instant())

        // assert
        assertThat(found).isPresent()
        assertThat(found.get().id).isEqualTo(saved.id)
        assertThat(found.get().roles).containsExactlyInAnyOrder("realm-admin")
        assertThat(found.get().expiresAt).isAfter(now)
    }

    @Test
    fun `findValidByRealmAndTokenHash does not return revoked invite`() {
        // arrange
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
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
            revoked = true,
            roles = setOf("realm-admin")
        )

        inviteRepository.saveAndFlush(invite)

        // act
        val found = inviteRepository.findValidByRealmAndTokenHash("master", "token-hash", clock.instant())

        // assert
        assertThat(found).isNotPresent()
    }

    @Test
    fun `existsActiveByRealmAndEmail respects expiry`() {
        // arrange
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        val expiresAt = now.plusSeconds(3600)
        val invite = InviteEntity(
            realm = "master",
            tokenHash = "token-hash",
            salt = "salt-value",
            email = "user@example.com",
            createdBy = "creator",
            createdAt = now,
            expiresAt = expiresAt,
            roles = setOf("realm-admin")
        )
        inviteRepository.saveAndFlush(invite)

        // act & assert
        assertThat(
            inviteRepository.existsActiveByRealmAndEmail("master", "user@example.com", clock.instant())
        ).isTrue()

        val pastInstant = clock.instant().plusSeconds(7200)
        assertThat(
            inviteRepository.existsActiveByRealmAndEmail("master", "user@example.com", pastInstant)
        ).isFalse()
    }

    @Test
    fun `existsActiveByRealmAndEmail ignores overused invites`() {
        // arrange
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        val invite = InviteEntity(
            realm = "master",
            tokenHash = "token-hash",
            salt = "salt-value",
            email = "user@example.com",
            createdBy = "creator",
            createdAt = now,
            expiresAt = now.plusSeconds(3600),
            maxUses = 1,
            uses = 1,
            roles = setOf("realm-admin")
        )
        inviteRepository.saveAndFlush(invite)

        // act & assert
        assertThat(
            inviteRepository.existsActiveByRealmAndEmail("master", "user@example.com", clock.instant())
        ).isFalse()
    }

    @Test
    fun `findValidByRealmAndTokenHash does not return overused invite`() {
        // arrange
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
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
            roles = setOf("realm-admin")
        )

        inviteRepository.saveAndFlush(invite)

        // act
        val found = inviteRepository.findValidByRealmAndTokenHash("master", "token-hash", clock.instant())

        // assert
        assertThat(found).isNotPresent()
    }

    @Test
    fun `findValidByIdForUpdate returns active invite`() {
        // arrange
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
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
            roles = setOf("realm-admin")
        )

        val saved = inviteRepository.saveAndFlush(invite)

        // act
        val found = inviteRepository.findValidByIdForUpdate(saved.id!!, clock.instant())

        // assert
        assertThat(found).isPresent()
        assertThat(found.get().id).isEqualTo(saved.id)
        assertThat(found.get().roles).containsExactlyInAnyOrder("realm-admin")
        assertThat(found.get().expiresAt).isAfter(now)
    }

    @Test
    fun `findValidByIdForUpdate does not return revoked invite`() {
        // arrange
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
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
            revoked = true,
            roles = setOf("realm-admin")
        )

        val saved = inviteRepository.saveAndFlush(invite)

        // act
        val found = inviteRepository.findValidByIdForUpdate(saved.id!!, clock.instant())

        // assert
        assertThat(found).isNotPresent()
    }

    @Test
    fun `findValidByIdForUpdate does not return overused invite`() {
        // arrange
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
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
            roles = setOf("realm-admin")
        )

        val saved = inviteRepository.saveAndFlush(invite)

        // act
        val found = inviteRepository.findValidByIdForUpdate(saved.id!!, clock.instant())

        // assert
        assertThat(found).isNotPresent()
    }
}
