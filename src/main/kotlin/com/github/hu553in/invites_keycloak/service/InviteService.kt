package com.github.hu553in.invites_keycloak.service

import com.github.hu553in.invites_keycloak.config.props.InviteProps
import com.github.hu553in.invites_keycloak.entity.InviteEntity
import com.github.hu553in.invites_keycloak.exception.ActiveInviteExistsException
import com.github.hu553in.invites_keycloak.exception.InvalidInviteException
import com.github.hu553in.invites_keycloak.exception.InviteNotFoundException
import com.github.hu553in.invites_keycloak.repo.InviteRepository
import com.github.hu553in.invites_keycloak.util.normalizeString
import com.github.hu553in.invites_keycloak.util.normalizeStrings
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

        val normalizedRealm = normalizeString(realm, "realm must not be blank")

        val realmConfig = inviteProps.realms[normalizedRealm]
        require(realmConfig != null) { "Realm $normalizedRealm is not configured for invite" }

        val now = clock.instant()
        val minExpiresAt = now.plus(inviteProps.expiry.min)
        val maxExpiresAt = now.plus(inviteProps.expiry.max)

        val normalizedEmail = normalizeString(email, "email must not be blank", true)
        inviteRepository.revokeExpired(normalizedRealm, normalizedEmail, now)
        inviteRepository.revokeOverused(normalizedRealm, normalizedEmail)

        val targetExpiresAt = expiresAt ?: now.plus(inviteProps.expiry.default)
        require(!targetExpiresAt.isBefore(minExpiresAt) && !targetExpiresAt.isAfter(maxExpiresAt)) {
            "expiresAt must be between ${inviteProps.expiry.min} and ${inviteProps.expiry.max} from now " +
                "(accepted: $minExpiresAt .. $maxExpiresAt)"
        }

        if (inviteRepository.existsActiveByRealmAndEmail(normalizedRealm, normalizedEmail, now)) {
            throw ActiveInviteExistsException(normalizedRealm, normalizedEmail)
        }

        val normalizedCreatedBy = normalizeString(createdBy, "createdBy must not be blank")

        val token = tokenService.generateToken()
        val salt = tokenService.generateSalt()
        val tokenHash = tokenService.hashToken(token, salt)

        val normalizedRoles = normalizeStrings(
            strings = roles,
            default = realmConfig.roles,
            required = false
        )

        val invite = InviteEntity(
            realm = normalizedRealm,
            tokenHash = tokenHash,
            salt = salt,
            email = normalizedEmail,
            createdBy = normalizedCreatedBy,
            createdAt = now,
            expiresAt = targetExpiresAt,
            maxUses = maxUses,
            roles = normalizedRoles
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
        val now = clock.instant()
        check(invite.isActive(now)) { "Invite $inviteId is not active; revoke is only allowed for active invites." }
        invite.revoked = true
    }

    @Transactional
    fun delete(inviteId: UUID): InviteEntity {
        val invite = inviteRepository.findById(inviteId)
            .orElseThrow { InviteNotFoundException(inviteId) }

        val now = clock.instant()
        check(!invite.isActive(now)) { "Invite $inviteId is active; revoke it before deleting." }

        inviteRepository.delete(invite)
        return invite
    }

    @Transactional
    fun resendInvite(inviteId: UUID, expiresAt: Instant, createdBy: String): CreatedInvite {
        val current = inviteRepository.findById(inviteId)
            .orElseThrow { InviteNotFoundException(inviteId) }

        current.revoked = true
        inviteRepository.flush()

        return createInvite(
            realm = current.realm,
            email = current.email,
            expiresAt = expiresAt,
            maxUses = current.maxUses,
            roles = current.roles,
            createdBy = createdBy
        )
    }

    @Transactional(readOnly = true)
    fun get(inviteId: UUID): InviteEntity {
        return inviteRepository.findById(inviteId)
            .orElseThrow { InviteNotFoundException(inviteId) }
    }

    private fun InviteEntity.isActive(now: Instant): Boolean {
        return !revoked && !expiresAt.isBefore(now) && uses < maxUses
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
