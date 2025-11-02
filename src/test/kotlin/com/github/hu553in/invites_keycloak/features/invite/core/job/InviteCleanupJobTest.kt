package com.github.hu553in.invites_keycloak.features.invite.core.job

import com.github.hu553in.invites_keycloak.features.invite.core.repo.InviteRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

@ExtendWith(MockitoExtension::class)
class InviteCleanupJobTest {

    @Mock
    private lateinit var inviteRepository: InviteRepository

    @Mock
    private lateinit var clock: Clock

    private lateinit var job: InviteCleanupJob

    @BeforeEach
    fun setUp() {
        job = InviteCleanupJob(inviteRepository, clock)
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
}
