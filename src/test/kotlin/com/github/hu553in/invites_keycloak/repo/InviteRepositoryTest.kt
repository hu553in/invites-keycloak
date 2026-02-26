package com.github.hu553in.invites_keycloak.repo

import com.github.hu553in.invites_keycloak.config.TestcontainersConfig
import com.github.hu553in.invites_keycloak.entity.InviteEntity
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestConstructor
import java.sql.Timestamp
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.*

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
    private val clock: Clock,
    private val jdbcClient: JdbcClient,
    private val entityManager: EntityManager,
) {

    @Test
    fun `findByRealmAndTokenHash returns active invite`() {
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
            roles = setOf("realm-admin"),
        )
        val saved = inviteRepository.saveAndFlush(invite)

        // act
        val found = inviteRepository.findByRealmAndTokenHash("master", "token-hash")

        // assert
        assertThat(found).isPresent()
        assertThat(found.get().id).isEqualTo(saved.id)
        assertThat(found.get().roles).containsExactlyInAnyOrder("realm-admin")
        assertThat(found.get().expiresAt).isAfter(now)
    }

    @Test
    fun `findByRealmAndTokenHash returns empty when realm differs`() {
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
            roles = setOf("realm-admin"),
        )
        inviteRepository.saveAndFlush(invite)

        // act
        val found = inviteRepository.findByRealmAndTokenHash("other-realm", "token-hash")

        // assert
        assertThat(found).isNotPresent()
    }

    @Test
    fun `findByRealmAndTokenHash returns empty when token hash differs`() {
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
            roles = setOf("realm-admin"),
        )
        inviteRepository.saveAndFlush(invite)

        // act
        val found = inviteRepository.findByRealmAndTokenHash("master", "different-token-hash")

        // assert
        assertThat(found).isNotPresent()
    }

    @Test
    fun `findByRealmAndTokenHash returns revoked invite`() {
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
            roles = setOf("realm-admin"),
        )
        inviteRepository.saveAndFlush(invite)

        // act
        val found = inviteRepository.findByRealmAndTokenHash("master", "token-hash")

        // assert
        assertThat(found).isPresent()
        assertThat(found.get().revoked).isTrue()
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
            roles = setOf("realm-admin"),
        )
        inviteRepository.saveAndFlush(invite)

        // act
        assertThat(
            inviteRepository.existsActiveByRealmAndEmail("master", "user@example.com", clock.instant()),
        )
            // assert
            .isTrue()

        // act
        val pastInstant = clock.instant().plusSeconds(7200)
        assertThat(
            inviteRepository.existsActiveByRealmAndEmail("master", "user@example.com", pastInstant),
        )
            // assert
            .isFalse()
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
            roles = setOf("realm-admin"),
        )
        inviteRepository.saveAndFlush(invite)

        // act
        assertThat(
            inviteRepository.existsActiveByRealmAndEmail("master", "user@example.com", clock.instant()),
        )
            // assert
            .isFalse()
    }

    @Test
    fun `findByRealmAndTokenHash returns overused invite`() {
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
            roles = setOf("realm-admin"),
        )
        inviteRepository.saveAndFlush(invite)

        // act
        val found = inviteRepository.findByRealmAndTokenHash("master", "token-hash")

        // assert
        assertThat(found).isPresent()
        assertThat(found.get().uses).isEqualTo(2)
    }

    @Test
    fun `findByRealmAndTokenHash returns expired invite`() {
        // arrange
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        val invite = InviteEntity(
            realm = "master",
            tokenHash = "expired-token-hash",
            salt = "salt-value",
            email = "user@example.com",
            createdBy = "creator",
            createdAt = now,
            expiresAt = now.plusSeconds(3600),
            maxUses = 2,
            roles = setOf("realm-admin"),
        )

        val saved = inviteRepository.saveAndFlush(invite)
        val expiredAt = now.minusSeconds(1)

        // column is not updatable in JPA -> use JDBC
        jdbcClient
            .sql("update invite set expires_at = ? where id = ?")
            .param(expiredAt.toSqlTimestamp())
            .param(saved.id)
            .update()
        entityManager.clear()

        // act
        val found = inviteRepository.findByRealmAndTokenHash("master", "expired-token-hash")

        // assert
        assertThat(found).isPresent()
        assertThat(found.get().expiresAt).isBeforeOrEqualTo(now)
    }

    @Test
    fun `findByIdForUpdate returns active invite`() {
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
            roles = setOf("realm-admin"),
        )
        val saved = inviteRepository.saveAndFlush(invite)

        // act
        val found = inviteRepository.findByIdForUpdate(saved.id!!)

        // assert
        assertThat(found).isPresent()
        assertThat(found.get().id).isEqualTo(saved.id)
        assertThat(found.get().roles).containsExactlyInAnyOrder("realm-admin")
        assertThat(found.get().expiresAt).isAfter(now)
    }

    @Test
    fun `findByIdForUpdate returns empty when invite is missing`() {
        // act
        val found = inviteRepository.findByIdForUpdate(UUID.randomUUID())

        // assert
        assertThat(found).isNotPresent()
    }

    @Test
    fun `findByIdForUpdate returns revoked invite`() {
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
            roles = setOf("realm-admin"),
        )
        val saved = inviteRepository.saveAndFlush(invite)

        // act
        val found = inviteRepository.findByIdForUpdate(saved.id!!)

        // assert
        assertThat(found).isPresent()
        assertThat(found.get().revoked).isTrue()
    }

    @Test
    fun `findByIdForUpdate returns overused invite`() {
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
            roles = setOf("realm-admin"),
        )
        val saved = inviteRepository.saveAndFlush(invite)

        // act
        val found = inviteRepository.findByIdForUpdate(saved.id!!)

        // assert
        assertThat(found).isPresent()
        assertThat(found.get().uses).isEqualTo(2)
    }

    @Test
    fun `findByIdForUpdate returns expired invite`() {
        // arrange
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        val invite = InviteEntity(
            realm = "master",
            tokenHash = "expired-token-hash",
            salt = "salt-value",
            email = "user@example.com",
            createdBy = "creator",
            createdAt = now,
            expiresAt = now.plusSeconds(3600),
            maxUses = 2,
            roles = setOf("realm-admin"),
        )

        val saved = inviteRepository.saveAndFlush(invite)
        val expiredAt = now.minusSeconds(1)

        // column is not updatable in JPA -> use JDBC
        jdbcClient.sql("update invite set expires_at = ? where id = ?")
            .param(expiredAt.toSqlTimestamp())
            .param(saved.id)
            .update()
        entityManager.clear()

        // act
        val found = inviteRepository.findByIdForUpdate(saved.id!!)

        // assert
        assertThat(found).isPresent()
        assertThat(found.get().expiresAt).isBeforeOrEqualTo(now)
    }

    private fun java.time.Instant.toSqlTimestamp(): Timestamp = Timestamp.from(this)
}
