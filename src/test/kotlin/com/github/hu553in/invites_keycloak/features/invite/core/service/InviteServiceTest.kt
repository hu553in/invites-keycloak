package com.github.hu553in.invites_keycloak.features.invite.core.service

import com.github.hu553in.invites_keycloak.bootstrap.InvitesKeycloakApplication
import com.github.hu553in.invites_keycloak.features.invite.config.InviteProps
import com.github.hu553in.invites_keycloak.features.invite.core.repo.InviteRepository
import com.github.hu553in.invites_keycloak.shared.config.TestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestConstructor
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
            inviteProps.realms.getValue("master").defaultRoles
        )
        assertThat(validated.uses).isZero()
        assertThat(rawToken).contains(".")
        assertThat(rawToken).doesNotContain(saved.tokenHash)
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
            .sql("update invite set expires_at = '$pastExpiresAt' where id = '${saved.id}'")
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

    private fun futureExpiresAt(minBuffer: Duration): Instant {
        return clock.instant()
            .plus(inviteProps.expiry.min)
            .plus(minBuffer)
    }
}
