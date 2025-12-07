package com.github.hu553in.invites_keycloak.service

import com.github.hu553in.invites_keycloak.InvitesKeycloakApplication
import com.github.hu553in.invites_keycloak.config.TestcontainersConfig
import com.github.hu553in.invites_keycloak.config.props.InviteProps
import com.github.hu553in.invites_keycloak.exception.ActiveInviteExistsException
import com.github.hu553in.invites_keycloak.exception.InvalidInviteException
import com.github.hu553in.invites_keycloak.repo.InviteRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestConstructor
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(classes = [InvitesKeycloakApplication::class])
@Import(TestcontainersConfig::class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class InviteServiceTest(
    private val inviteService: InviteService,
    private val inviteRepository: InviteRepository,
    private val inviteProps: InviteProps,
    private val clock: Clock,
    private val jdbcClient: JdbcClient
) {

    @AfterEach
    fun tearDown() {
        inviteRepository.deleteAllInBatch()
    }

    @Test
    fun `createInvite persists invite and validateToken succeeds`() {
        // arrange
        val expiresAt = futureExpiresAt(minBuffer = Duration.ofMinutes(10))

        // act
        val (saved, rawToken) = inviteService.createInvite(
            realm = "master",
            email = "User@Example.com",
            expiresAt = expiresAt,
            maxUses = 2,
            roles = emptySet(),
            createdBy = "creator"
        )

        val validated = inviteService.validateToken("master", rawToken)

        // assert
        assertThat(saved.id).isNotNull()
        assertThat(validated.id).isEqualTo(saved.id)
        assertThat(validated.email).isEqualTo("user@example.com")
        assertThat(validated.roles).containsExactlyInAnyOrderElementsOf(
            inviteProps.realms.getValue("master").roles
        )
        assertThat(validated.uses).isZero()
        assertThat(rawToken).contains(".")
        assertThat(rawToken).doesNotContain(saved.tokenHash)
    }

    @Test
    fun `createInvite allows realm with empty configured roles`() {
        // arrange
        val expiresAt = futureExpiresAt(minBuffer = Duration.ofMinutes(10))

        // act
        val (saved, rawToken) = inviteService.createInvite(
            realm = "no-roles",
            email = "user@example.com",
            expiresAt = expiresAt,
            maxUses = 2,
            roles = emptySet(),
            createdBy = "creator"
        )

        val validated = inviteService.validateToken("no-roles", rawToken)

        // assert
        assertThat(saved.roles).isEmpty()
        assertThat(validated.roles).isEmpty()
    }

    @Test
    fun `validateToken throws InvalidInviteException when invite is expired`() {
        // arrange
        val expiresAt = futureExpiresAt(minBuffer = Duration.ofMinutes(10))
        val (saved, rawToken) = inviteService.createInvite(
            realm = "master",
            email = "user@example.com",
            expiresAt = expiresAt,
            roles = setOf("user"),
            createdBy = "creator"
        )

        val pastExpiresAt = clock.instant().minus(Duration.ofMinutes(20))

        // column is not updatable in JPA -> use JDBC
        jdbcClient
            .sql("update invite set expires_at = ? where id = ?")
            .param(pastExpiresAt.toSqlTimestamp())
            .param(saved.id)
            .update()

        // act
        assertThatThrownBy { inviteService.validateToken("master", rawToken) }
            // assert
            .isInstanceOf(InvalidInviteException::class.java)
    }

    @Test
    fun `useOnce is atomic under concurrent access`() {
        // arrange
        val expiresAt = futureExpiresAt(minBuffer = Duration.ofHours(1))
        val saved = inviteService.createInvite(
            realm = "master",
            email = "user@example.com",
            expiresAt = expiresAt,
            roles = setOf("user"),
            createdBy = "creator"
        ).invite

        val executor = Executors.newFixedThreadPool(2)
        val successCount = AtomicInteger(0)
        val startLatch = CountDownLatch(1)
        val readyLatch = CountDownLatch(2)

        try {
            // act
            val futures: List<Future<Result<*>>> = List(2) {
                executor.submit<Result<*>> {
                    readyLatch.countDown()
                    startLatch.await(5, TimeUnit.SECONDS)
                    runCatching { inviteService.useOnce(saved.id!!) }
                }
            }

            readyLatch.await(5, TimeUnit.SECONDS)
            startLatch.countDown()

            // assert
            futures.forEach { future ->
                val result = future.get(10, TimeUnit.SECONDS)
                if (result.isSuccess) {
                    successCount.incrementAndGet()
                } else {
                    assertThat(result.exceptionOrNull()).isInstanceOf(InvalidInviteException::class.java)
                }
            }
        } finally {
            executor.shutdownNow()
        }

        val persisted = inviteRepository.findById(saved.id!!).orElseThrow()
        assertThat(successCount.get()).isEqualTo(1)
        assertThat(persisted.uses).isEqualTo(1)
    }

    @Test
    fun `createInvite allows issuing new invite after previous expired`() {
        // arrange
        val expiresAt = futureExpiresAt(minBuffer = Duration.ofMinutes(10))
        val first = inviteService.createInvite(
            realm = "master",
            email = "user@example.com",
            expiresAt = expiresAt,
            roles = setOf("user"),
            createdBy = "creator"
        ).invite

        val pastExpiresAt = clock.instant().minus(Duration.ofMinutes(5))
        jdbcClient
            .sql("update invite set expires_at = ? where id = ?")
            .param(pastExpiresAt.toSqlTimestamp())
            .param(first.id)
            .update()

        // act
        val second = inviteService.createInvite(
            realm = "master",
            email = "user@example.com",
            roles = setOf("user"),
            createdBy = "creator"
        ).invite

        // assert
        assertThat(second.id).isNotEqualTo(first.id)
    }

    @Test
    fun `resendInvite uses provided expiry and revokes original invite`() {
        // arrange
        val original = inviteService.createInvite(
            realm = "master",
            email = "user@example.com",
            roles = setOf("user"),
            createdBy = "creator"
        ).invite
        val newExpiry = clock.instant().plus(Duration.ofHours(6))

        // act
        val resent = inviteService.resendInvite(
            inviteId = original.id!!,
            expiresAt = newExpiry,
            createdBy = "resender"
        )

        // assert
        val revokedOriginal = inviteRepository.findById(original.id!!).orElseThrow()
        assertThat(revokedOriginal.revoked).isTrue()
        assertThat(resent.invite.id).isNotEqualTo(original.id)
        assertThat(resent.invite.expiresAt).isEqualTo(newExpiry)
        assertThat(resent.invite.email).isEqualTo(original.email)
    }

    @Test
    fun `resendInvite allows already revoked invite`() {
        // arrange
        val original = inviteService.createInvite(
            realm = "master",
            email = "user@example.com",
            roles = setOf("user"),
            createdBy = "creator"
        ).invite
        inviteService.revoke(original.id!!)
        val newExpiry = clock.instant().plus(Duration.ofHours(12))

        // act
        val resent = inviteService.resendInvite(
            inviteId = original.id!!,
            expiresAt = newExpiry,
            createdBy = "resender"
        )

        // assert
        val revokedOriginal = inviteRepository.findById(original.id!!).orElseThrow()
        assertThat(revokedOriginal.revoked).isTrue()
        assertThat(resent.invite.id).isNotEqualTo(original.id)
        assertThat(resent.invite.expiresAt).isEqualTo(newExpiry)
        assertThat(resent.invite.email).isEqualTo(original.email)
    }

    @Test
    fun `createInvite allows new invite after max uses reached`() {
        // arrange
        val initial = inviteService.createInvite(
            realm = "master",
            email = "user@example.com",
            roles = setOf("user"),
            createdBy = "creator",
            maxUses = 1
        ).invite

        inviteService.useOnce(initial.id!!)

        // act
        val replacement = inviteService.createInvite(
            realm = "master",
            email = "user@example.com",
            roles = setOf("user"),
            createdBy = "creator"
        ).invite

        // assert
        val original = inviteRepository.findById(initial.id!!).orElseThrow()
        assertThat(original.revoked).isTrue()
        assertThat(replacement.id).isNotEqualTo(initial.id)
    }

    @Test
    fun `revoke rejects used-up invite`() {
        // arrange
        val invite = inviteService.createInvite(
            realm = "master",
            email = "user@example.com",
            roles = setOf("user"),
            createdBy = "creator",
            maxUses = 1
        ).invite
        inviteService.useOnce(invite.id!!)

        // act & assert
        assertThatThrownBy { inviteService.revoke(invite.id!!) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `revoke rejects expired invite`() {
        // arrange
        val expiresAt = futureExpiresAt(minBuffer = Duration.ofMinutes(10))
        val invite = inviteService.createInvite(
            realm = "master",
            email = "user@example.com",
            roles = setOf("user"),
            createdBy = "creator",
            expiresAt = expiresAt
        ).invite

        val pastExpiresAt = clock.instant().minus(Duration.ofMinutes(5))
        jdbcClient
            .sql("update invite set expires_at = ? where id = ?")
            .param(pastExpiresAt.toSqlTimestamp())
            .param(invite.id)
            .update()

        // act & assert
        assertThatThrownBy { inviteService.revoke(invite.id!!) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `delete removes revoked invite`() {
        // arrange
        val invite = inviteService.createInvite(
            realm = "master",
            email = "user@example.com",
            roles = setOf("user"),
            createdBy = "creator"
        ).invite
        inviteService.revoke(invite.id!!)

        // act
        val deleted = inviteService.delete(invite.id!!)

        // assert
        assertThat(deleted.id).isEqualTo(invite.id)
        assertThat(inviteRepository.findById(invite.id!!)).isEmpty()
    }

    @Test
    fun `delete rejects active invite`() {
        // arrange
        val invite = inviteService.createInvite(
            realm = "master",
            email = "user@example.com",
            roles = setOf("user"),
            createdBy = "creator"
        ).invite

        // act & assert
        assertThatThrownBy { inviteService.delete(invite.id!!) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(inviteRepository.findById(invite.id!!)).isPresent()
    }

    @Test
    fun `createInvite rejects when active invite already exists`() {
        // arrange
        inviteService.createInvite(
            realm = "master",
            email = "user@example.com",
            roles = setOf("user"),
            createdBy = "creator"
        )

        // act & assert
        assertThatThrownBy {
            inviteService.createInvite(
                realm = "master",
                email = "user@example.com",
                roles = setOf("user"),
                createdBy = "another"
            )
        }.isInstanceOf(ActiveInviteExistsException::class.java)
    }

    private fun futureExpiresAt(minBuffer: Duration): Instant {
        return clock.instant()
            .plus(inviteProps.expiry.min)
            .plus(minBuffer)
    }

    private fun Instant.toSqlTimestamp(): Timestamp = Timestamp.from(this)
}
