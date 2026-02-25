package com.github.hu553in.invites_keycloak.job

import com.github.hu553in.invites_keycloak.config.props.InviteProps
import com.github.hu553in.invites_keycloak.repo.InviteRepository
import com.github.hu553in.invites_keycloak.util.DELETED_COUNT_KEY
import com.github.hu553in.invites_keycloak.util.RETENTION_DAYS_KEY
import com.github.hu553in.invites_keycloak.util.SYSTEM_USER_ID
import com.github.hu553in.invites_keycloak.util.logger
import com.github.hu553in.invites_keycloak.util.withAuthDataInMdc
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.concurrent.TimeUnit

@Component
@Profile("!test")
class InviteCleanupJob(
    private val inviteRepository: InviteRepository,
    private val clock: Clock,
    private val inviteProps: InviteProps
) {

    private val log by logger()

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.DAYS)
    @Transactional
    fun cleanupInvites() {
        withAuthDataInMdc(SYSTEM_USER_ID) {
            val retention = inviteProps.cleanup.retention
            val cutoff = clock.instant().minus(retention)
            try {
                log.atDebug()
                    .addKeyValue(RETENTION_DAYS_KEY) { retention.toDays() }
                    .log { "Starting invite cleanup job" }

                val deleted = inviteRepository.deleteByExpiresAtBefore(cutoff)
                if (deleted > 0) {
                    log.atInfo()
                        .addKeyValue(DELETED_COUNT_KEY) { deleted }
                        .addKeyValue(RETENTION_DAYS_KEY) { retention.toDays() }
                        .log { "Cleaned up expired invites prior to retention period" }
                } else {
                    log.atDebug()
                        .addKeyValue(RETENTION_DAYS_KEY) { retention.toDays() }
                        .log { "Invite cleanup job finished with no deletions" }
                }
            } catch (
                @Suppress("TooGenericExceptionCaught")
                e: Exception
            ) {
                log.atError()
                    .addKeyValue(RETENTION_DAYS_KEY) { retention.toDays() }
                    .setCause(e)
                    .log { "Invite cleanup job failed" }
                throw e
            }
        }
    }
}
