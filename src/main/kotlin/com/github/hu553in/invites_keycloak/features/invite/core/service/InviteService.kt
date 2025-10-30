package com.github.hu553in.invites_keycloak.features.invite.core.service

import com.github.hu553in.invites_keycloak.features.invite.config.InviteProps
import com.github.hu553in.invites_keycloak.features.invite.core.model.InviteEntity
import com.github.hu553in.invites_keycloak.features.invite.core.repo.InviteRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.*

private const val RAW_TOKEN_DELIMITER = '.'

@Service
class InviteService(
    private val inviteRepository: InviteRepository,
    private val tokenService: TokenService,
    private val inviteProps: InviteProps,
    private val clock: Clock
) {

    @Transactional(readOnly = true)
    fun listInvites(): List<InviteEntity> {
        return inviteRepository.findAllByOrderByCreatedAtDesc()
    }

    @Transactional
    fun createInvite(
        realm: String,
        email: String,
        expiresAt: Instant? = null,
        maxUses: Int = 1,
        roles: Set<String>,
        createdBy: String
    ): CreatedInvite {
        require(maxUses >= 1) { "maxUses must be >= 1" }

        val realmConfig = inviteProps.realms[realm]
        require(realmConfig != null) { "Realm $realm is not configured for invite" }

        val now = clock.instant()
        val minExpiresAt = now.plus(inviteProps.expiry.min)
        val maxExpiresAt = now.plus(inviteProps.expiry.max)

        val targetExpiresAt = expiresAt ?: now.plus(inviteProps.expiry.default)
        require(!targetExpiresAt.isBefore(minExpiresAt) && !targetExpiresAt.isAfter(maxExpiresAt)) {
            "expiresAt must be between ${inviteProps.expiry.min} and ${inviteProps.expiry.max} from now " +
                "(accepted: $minExpiresAt .. $maxExpiresAt)"
        }

        val rolesToPersist = roles.ifEmpty { realmConfig.defaultRoles }
        require(rolesToPersist.isNotEmpty()) {
            "Invite must contain at least one role (either provided or realm default)"
        }

        val normalizedEmail = email.trim().lowercase(Locale.ROOT)
        require(email.isNotBlank()) { "email must not be blank" }

        val normalizedCreatedBy = createdBy.trim()
        require(normalizedCreatedBy.isNotBlank()) { "createdBy must not be blank" }

        val token = tokenService.generateToken()
        val salt = tokenService.generateSalt()
        val tokenHash = tokenService.hashToken(token, salt)

        val invite = InviteEntity(
            realm = realm,
            tokenHash = tokenHash,
            salt = salt,
            email = normalizedEmail,
            createdBy = normalizedCreatedBy,
            createdAt = now,
            expiresAt = targetExpiresAt,
            maxUses = maxUses,
            roles = rolesToPersist
        )

        val saved = inviteRepository.save(invite)
        val rawToken = buildRawToken(token, salt)
        return CreatedInvite(saved, rawToken)
    }

    @Transactional(readOnly = true)
    fun validateToken(realm: String, rawToken: String): InviteEntity {
        val (token, salt) = parseRawToken(rawToken)
        val tokenHash = tokenService.hashToken(token, salt)

        return inviteRepository.findValidByRealmAndTokenHash(realm, tokenHash, clock.instant())
            .orElseThrow { InvalidInviteException() }
    }

    @Transactional
    fun useOnce(inviteId: UUID): InviteEntity {
        return inviteRepository.findValidByIdForUpdate(inviteId, clock.instant())
            .orElseThrow { InvalidInviteException() }
            .also { it.incrementUses() }
    }

    @Transactional
    fun revoke(inviteId: UUID) {
        val invite = inviteRepository.findById(inviteId)
            .orElseThrow { InviteNotFoundException(inviteId) }
        invite.revoked = true
    }

    @Transactional(readOnly = true)
    fun get(inviteId: UUID): InviteEntity {
        return inviteRepository.findById(inviteId)
            .orElseThrow { InviteNotFoundException(inviteId) }
    }

    private fun parseRawToken(rawToken: String): Pair<String, String> {
        val parts = rawToken.split(RAW_TOKEN_DELIMITER, limit = 2)
        if (parts.size != 2 || parts.any { it.isBlank() }) {
            throw InvalidInviteException()
        }
        return parts[0] to parts[1]
    }

    private fun buildRawToken(token: String, salt: String): String = "$token$RAW_TOKEN_DELIMITER$salt"

    data class CreatedInvite(val invite: InviteEntity, val rawToken: String)
}
