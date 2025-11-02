package com.github.hu553in.invites_keycloak.features.invite.core.job

import com.github.hu553in.invites_keycloak.features.invite.core.repo.InviteRepository
import com.github.hu553in.invites_keycloak.shared.util.logger
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

@Component
@Profile("!test")
class InviteCleanupJob(
    private val inviteRepository: InviteRepository,
    private val clock: Clock
) {

    private val log by logger()

    companion object {
        private const val RETENTION_DAYS = 30L
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.DAYS)
    fun cleanupInvites() {
        val cutoff = clock.instant().minus(RETENTION_DAYS, ChronoUnit.DAYS)
        val deleted = inviteRepository.deleteByExpiresAtBefore(cutoff)
        if (deleted > 0) {
            log.atInfo()
                .addKeyValue("deleted_count") { deleted }
                .log { "Deleted expired invites older than $RETENTION_DAYS days" }
        }
    }
}
