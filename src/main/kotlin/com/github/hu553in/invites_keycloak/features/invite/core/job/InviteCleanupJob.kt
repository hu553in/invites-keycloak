package com.github.hu553in.invites_keycloak.features.invite.core.job

import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
@Profile("!test")
class InviteCleanupJob {

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.DAYS)
    fun cleanupInvites() = Unit
}
