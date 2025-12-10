package com.github.hu553in.invites_keycloak.service

import com.github.hu553in.invites_keycloak.config.props.InviteProps
import com.github.hu553in.invites_keycloak.entity.InviteEntity
import com.github.hu553in.invites_keycloak.exception.ActiveInviteExistsException
import com.github.hu553in.invites_keycloak.exception.InvalidInviteException
import com.github.hu553in.invites_keycloak.exception.InviteNotFoundException
import com.github.hu553in.invites_keycloak.repo.InviteRepository
import com.github.hu553in.invites_keycloak.util.SYSTEM_USER_ID
import com.github.hu553in.invites_keycloak.util.logger
import com.github.hu553in.invites_keycloak.util.maskSensitive
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

    private val log by logger()

    @Transactional(readOnly = true)
    fun listInvites(): List<InviteEntity> {
        val invites = inviteRepository.findAllByOrderByCreatedAtDesc()
        log.atDebug()
            .addKeyValue("invite.count") { invites.size }
            .log { "Listed invites" }
        return invites
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
        val expiredRevoked = inviteRepository.revokeExpired(normalizedRealm, normalizedEmail, now, SYSTEM_USER_ID)
        val overusedRevoked = inviteRepository.revokeOverused(normalizedRealm, normalizedEmail, now, SYSTEM_USER_ID)
        log.atDebug()
            .addKeyValue("realm") { normalizedRealm }
            .addKeyValue("email") { maskSensitive(normalizedEmail) }
            .addKeyValue("revoked_expired") { expiredRevoked }
            .addKeyValue("revoked_overused") { overusedRevoked }
            .log { "Revoked stale invites before creating a new one" }

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
        log.atInfo()
            .addKeyValue("invite.id") { saved.id }
            .addKeyValue("realm") { normalizedRealm }
            .addKeyValue("email") { maskSensitive(normalizedEmail) }
            .addKeyValue("expires_at") { targetExpiresAt }
            .addKeyValue("max_uses") { maxUses }
            .addKeyValue("roles") { normalizedRoles.joinToString(",") }
            .addKeyValue("created_by") { normalizedCreatedBy }
            .log { "Created invite" }
        return CreatedInvite(saved, rawToken)
    }

    @Transactional(readOnly = true)
    fun validateToken(realm: String, rawToken: String): InviteEntity {
        return try {
            val (token, salt) = parseRawToken(rawToken)
            val tokenHash = tokenService.hashToken(token, salt)

            inviteRepository.findValidByRealmAndTokenHash(realm, tokenHash, clock.instant())
                .map {
                    log.atDebug()
                        .addKeyValue("invite.id") { it.id }
                        .addKeyValue("realm") { it.realm }
                        .log { "Validated invite token" }
                    it
                }
                .orElseThrow { InvalidInviteException() }
        } catch (e: IllegalArgumentException) {
            log.atDebug()
                .addKeyValue("realm") { realm }
                .addKeyValue("token_length") { rawToken.length }
                .setCause(e)
                .log { "Invite token parsing failed" }
            throw InvalidInviteException("Invite token is malformed", e)
        }
    }

    @Transactional
    fun useOnce(inviteId: UUID): InviteEntity {
        return inviteRepository.findValidByIdForUpdate(inviteId, clock.instant())
            .orElseThrow { InvalidInviteException() }
            .also {
                it.incrementUses()
                log.atInfo()
                    .addKeyValue("invite.id") { inviteId }
                    .addKeyValue("uses") { it.uses }
                    .addKeyValue("max_uses") { it.maxUses }
                    .log { "Marked invite as used once" }
            }
    }

    @Transactional
    fun revoke(inviteId: UUID, revokedBy: String = SYSTEM_USER_ID) {
        val invite = inviteRepository.findById(inviteId)
            .orElseThrow { InviteNotFoundException(inviteId) }
        val now = clock.instant()
        val normalizedRevokedBy = normalizeString(revokedBy, "revokedBy must not be blank")
        check(invite.isActive(now)) { "Invite $inviteId is not active; revoke is only allowed for active invites." }
        invite.markRevoked(normalizedRevokedBy, now)
        log.atInfo()
            .addKeyValue("invite.id") { inviteId }
            .addKeyValue("realm") { invite.realm }
            .addKeyValue("email") { maskSensitive(invite.email) }
            .addKeyValue("revoked_by") { normalizedRevokedBy }
            .log { "Revoked invite" }
    }

    @Transactional
    fun delete(inviteId: UUID, deletedBy: String = SYSTEM_USER_ID): InviteEntity {
        val invite = inviteRepository.findById(inviteId)
            .orElseThrow { InviteNotFoundException(inviteId) }

        val now = clock.instant()
        check(!invite.isActive(now)) { "Invite $inviteId is active; revoke it before deleting." }

        val normalizedDeletedBy = normalizeString(deletedBy, "deletedBy must not be blank")
        inviteRepository.delete(invite)
        log.atInfo()
            .addKeyValue("invite.id") { inviteId }
            .addKeyValue("realm") { invite.realm }
            .addKeyValue("email") { maskSensitive(invite.email) }
            .addKeyValue("deleted_by") { normalizedDeletedBy }
            .log { "Deleted invite" }
        return invite
    }

    @Transactional
    fun resendInvite(inviteId: UUID, expiresAt: Instant, createdBy: String): CreatedInvite {
        val normalizedCreatedBy = normalizeString(createdBy, "createdBy must not be blank")
        val current = inviteRepository.findById(inviteId)
            .orElseThrow { InviteNotFoundException(inviteId) }

        val now = clock.instant()
        current.markRevoked(normalizedCreatedBy, now)
        inviteRepository.flush()

        val created = createInvite(
            realm = current.realm,
            email = current.email,
            expiresAt = expiresAt,
            maxUses = current.maxUses,
            roles = current.roles,
            createdBy = normalizedCreatedBy
        )
        log.atInfo()
            .addKeyValue("invite.id.revoked") { inviteId }
            .addKeyValue("invite.id.created") { created.invite.id }
            .addKeyValue("realm") { created.invite.realm }
            .addKeyValue("email") { maskSensitive(created.invite.email) }
            .addKeyValue("expires_at") { expiresAt }
            .addKeyValue("resent_by") { normalizedCreatedBy }
            .log { "Resent invite (previous invite revoked)" }
        return created
    }

    @Transactional(readOnly = true)
    fun get(inviteId: UUID): InviteEntity {
        return inviteRepository.findById(inviteId)
            .map {
                log.atDebug()
                    .addKeyValue("invite.id") { inviteId }
                    .addKeyValue("realm") { it.realm }
                    .log { "Fetched invite" }
                it
            }
            .orElseThrow { InviteNotFoundException(inviteId) }
    }

    private fun InviteEntity.isActive(now: Instant): Boolean {
        return !revoked && !expiresAt.isBefore(now) && uses < maxUses
    }

    private fun InviteEntity.markRevoked(revokedBy: String, now: Instant) {
        val normalizedRevokedBy = normalizeString(revokedBy, "revokedBy must not be blank")
        revoked = true
        if (revokedAt == null) {
            revokedAt = now
        }
        if (this.revokedBy.isNullOrBlank()) {
            this.revokedBy = normalizedRevokedBy
        }
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
