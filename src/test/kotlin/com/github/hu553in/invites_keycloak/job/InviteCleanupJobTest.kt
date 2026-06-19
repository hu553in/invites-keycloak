package com.github.hu553in.invites_keycloak.job

import com.github.hu553in.invites_keycloak.config.props.InviteProps
import com.github.hu553in.invites_keycloak.repo.InviteRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.mock
import org.mockito.BDDMockito.then
import org.mockito.BDDMockito.willThrow
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

@ExtendWith(MockitoExtension::class)
class InviteCleanupJobTest {

    @Mock
    private lateinit var inviteRepository: InviteRepository

    @Mock
    private lateinit var clock: Clock

    private lateinit var inviteProps: InviteProps
    private lateinit var job: InviteCleanupJob

    @BeforeEach
    fun setUp() {
        inviteProps = InviteProps(
            publicBaseUrl = "https://app.example.com",
            expiry = mock(),
            realms = mock(),
            token = mock(),
            cleanup = InviteProps.CleanupProps(retention = Duration.ofDays(30)),
        )
        job = InviteCleanupJob(inviteRepository, clock, inviteProps)
    }

    @Test
    fun `cleanupInvites removes records older than retention`() {
        // arrange
        val now = Instant.parse("2025-01-31T10:15:30Z")
        given(clock.instant()).willReturn(now)

        val expectedCutoff = now.minus(30, ChronoUnit.DAYS)
        given(inviteRepository.deleteByExpiresAtBefore(expectedCutoff)).willReturn(2)

        // act
        job.cleanupInvites()

        // assert
        then(inviteRepository).should().deleteByExpiresAtBefore(expectedCutoff)
    }

    @Test
    fun `cleanupInvites is transactional`() {
        // arrange
        val method = InviteCleanupJob::class.java.getDeclaredMethod("cleanupInvites")

        // assert
        assertThat(method.isAnnotationPresent(Transactional::class.java)).isTrue()
    }

    @Test
    fun `cleanupInvites rethrows repository failure after logging`() {
        // arrange
        val now = Instant.parse("2025-01-31T10:15:30Z")
        given(clock.instant()).willReturn(now)
        willThrow(RuntimeException("db down"))
            .given(inviteRepository)
            .deleteByExpiresAtBefore(now.minus(30, ChronoUnit.DAYS))

        // act
        assertThatThrownBy { job.cleanupInvites() }
            // assert
            .isInstanceOf(RuntimeException::class.java)
            .hasMessage("db down")
    }
}
