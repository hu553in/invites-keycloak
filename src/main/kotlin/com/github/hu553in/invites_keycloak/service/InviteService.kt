package com.github.hu553in.invites_keycloak.service

import com.github.hu553in.invites_keycloak.config.props.InviteProps
import com.github.hu553in.invites_keycloak.entity.InviteEntity
import com.github.hu553in.invites_keycloak.exception.ActiveInviteExistsException
import com.github.hu553in.invites_keycloak.exception.InvalidInviteException
import com.github.hu553in.invites_keycloak.exception.InvalidInviteReason
import com.github.hu553in.invites_keycloak.exception.InviteNotFoundException
import com.github.hu553in.invites_keycloak.repo.InviteRepository
import com.github.hu553in.invites_keycloak.util.INVITE_COUNT_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_CREATED_BY_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_CREATED_ID_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_DELETED_BY_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_EXPIRES_AT_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_ID_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_MAX_USES_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_PREVIOUS_ID_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_RESENT_BY_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_REVOKED_BY_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_REVOKED_EXPIRED_COUNT_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_REVOKED_OVERUSED_COUNT_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_ROLES_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_TOKEN_LENGTH_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_USES_KEY
import com.github.hu553in.invites_keycloak.util.KEYCLOAK_REALM_KEY
import com.github.hu553in.invites_keycloak.util.SYSTEM_USER_ID
import com.github.hu553in.invites_keycloak.util.logger
import com.github.hu553in.invites_keycloak.util.normalizeString
import com.github.hu553in.invites_keycloak.util.normalizeStrings
import com.github.hu553in.invites_keycloak.util.withInviteContextInMdc
import com.github.hu553in.invites_keycloak.util.withMdc
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
    private val clock: Clock,
) {

    private val log by logger()

    @Transactional(readOnly = true)
    fun listInvites(): List<InviteEntity> {
        val invites = inviteRepository.findAllByOrderByCreatedAtDesc()
        log.atDebug()
            .addKeyValue(INVITE_COUNT_KEY) { invites.size }
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
        createdBy: String,
    ): CreatedInvite {
        require(maxUses >= 1) { "maxUses must be >= 1" }

        val normalizedRealm = normalizeString(realm, "realm must not be blank")

        val realmConfig = inviteProps.realms[normalizedRealm]
        require(realmConfig != null) { "Realm $normalizedRealm is not configured for invite" }

        val now = clock.instant()
        val minExpiresAt = now.plus(inviteProps.expiry.min)
        val maxExpiresAt = now.plus(inviteProps.expiry.max)

        val normalizedEmail = normalizeString(email, "email must not be blank", true)
        return withInviteContextInMdc(inviteId = null, realm = normalizedRealm, email = normalizedEmail) {
            val expiredRevoked = inviteRepository.revokeExpired(normalizedRealm, normalizedEmail, now, SYSTEM_USER_ID)
            val overusedRevoked = inviteRepository.revokeOverused(normalizedRealm, normalizedEmail, now, SYSTEM_USER_ID)
            log.atDebug()
                .addKeyValue(INVITE_REVOKED_EXPIRED_COUNT_KEY) { expiredRevoked }
                .addKeyValue(INVITE_REVOKED_OVERUSED_COUNT_KEY) { overusedRevoked }
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
                required = false,
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
                roles = normalizedRoles,
            )

            val saved = inviteRepository.save(invite)
            val rawToken = buildRawToken(token, salt)
            log.atInfo()
                .addKeyValue(INVITE_ID_KEY) { saved.id }
                .addKeyValue(INVITE_EXPIRES_AT_KEY) { targetExpiresAt }
                .addKeyValue(INVITE_MAX_USES_KEY) { maxUses }
                .addKeyValue(INVITE_ROLES_KEY) { normalizedRoles.joinToString(",") }
                .addKeyValue(INVITE_CREATED_BY_KEY) { normalizedCreatedBy }
                .log { "Created invite" }
            CreatedInvite(saved, rawToken)
        }
    }

    @Transactional(readOnly = true)
    fun validateToken(realm: String, rawToken: String): InviteEntity = withMdc(KEYCLOAK_REALM_KEY to realm) {
        try {
            val now = clock.instant()
            val (token, salt) = parseRawToken(rawToken)
            val tokenHash = tokenService.hashToken(token, salt)

            val invite = inviteRepository.findByRealmAndTokenHash(realm, tokenHash)
                .orElseThrow(::InviteNotFoundException)

            throwIfInviteUnavailable(invite, now)

            log.atDebug()
                .addKeyValue(INVITE_ID_KEY) { invite.id }
                .log { "Validated invite token" }

            invite
        } catch (e: IllegalArgumentException) {
            log.atDebug()
                .addKeyValue(INVITE_TOKEN_LENGTH_KEY) { rawToken.length }
                .setCause(e)
                .log { "Invite token parsing failed" }
            throw InvalidInviteException(cause = e, reason = InvalidInviteReason.MALFORMED)
        }
    }

    @Transactional
    fun useOnce(inviteId: UUID): InviteEntity {
        val now = clock.instant()
        return inviteRepository.findByIdForUpdate(inviteId)
            .orElseThrow(::InviteNotFoundException)
            .also { throwIfInviteUnavailable(it, now) }
            .also {
                it.incrementUses()
                log.atInfo()
                    .addKeyValue(INVITE_USES_KEY) { it.uses }
                    .addKeyValue(INVITE_MAX_USES_KEY) { it.maxUses }
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
            .addKeyValue(INVITE_REVOKED_BY_KEY) { normalizedRevokedBy }
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
            .addKeyValue(INVITE_DELETED_BY_KEY) { normalizedDeletedBy }
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
            createdBy = normalizedCreatedBy,
        )
        log.atInfo()
            .addKeyValue(INVITE_PREVIOUS_ID_KEY) { inviteId }
            .addKeyValue(INVITE_CREATED_ID_KEY) { created.invite.id }
            .addKeyValue(INVITE_EXPIRES_AT_KEY) { expiresAt }
            .addKeyValue(INVITE_RESENT_BY_KEY) { normalizedCreatedBy }
            .log { "Resent invite (previous invite revoked)" }
        return created
    }

    @Transactional(readOnly = true)
    fun get(inviteId: UUID): InviteEntity = inviteRepository.findById(inviteId)
        .map {
            log.atDebug()
                .addKeyValue(INVITE_ID_KEY) { inviteId }
                .addKeyValue(KEYCLOAK_REALM_KEY) { it.realm }
                .log { "Fetched invite" }
            it
        }
        .orElseThrow { InviteNotFoundException(inviteId) }

    private fun InviteEntity.isActive(now: Instant): Boolean = !revoked && !expiresAt.isBefore(now) && uses < maxUses

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
        require(parts.size == 2 && parts.none { it.isBlank() }) { "Malformed invite token format" }
        return parts[0] to parts[1]
    }

    private fun throwIfInviteUnavailable(invite: InviteEntity, now: Instant) {
        val reason = when {
            !invite.expiresAt.isAfter(now) -> InvalidInviteReason.EXPIRED
            invite.uses >= invite.maxUses -> InvalidInviteReason.OVERUSED
            invite.revoked -> InvalidInviteReason.REVOKED
            else -> return
        }
        throw InvalidInviteException(reason = reason)
    }

    private fun buildRawToken(token: String, salt: String): String = "$token$RAW_TOKEN_DELIMITER$salt"

    data class CreatedInvite(val invite: InviteEntity, val rawToken: String)
}
