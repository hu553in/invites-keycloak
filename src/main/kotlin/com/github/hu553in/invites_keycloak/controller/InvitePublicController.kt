package com.github.hu553in.invites_keycloak.controller

import com.github.hu553in.invites_keycloak.client.KeycloakAdminClient
import com.github.hu553in.invites_keycloak.exception.InvalidInviteException
import com.github.hu553in.invites_keycloak.exception.InviteNotFoundException
import com.github.hu553in.invites_keycloak.service.InviteService
import com.github.hu553in.invites_keycloak.util.ErrorMessages
import com.github.hu553in.invites_keycloak.util.INVITE_FLOW_SHOULD_REVOKE_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_INVALID_REASON_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_ROLES_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_TOKEN_LENGTH_KEY
import com.github.hu553in.invites_keycloak.util.KEYCLOAK_REALM_KEY
import com.github.hu553in.invites_keycloak.util.REQUEST_STATUS_KEY
import com.github.hu553in.invites_keycloak.util.SYSTEM_USER_ID
import com.github.hu553in.invites_keycloak.util.USER_ID_KEY
import com.github.hu553in.invites_keycloak.util.dedupedEventForAppError
import com.github.hu553in.invites_keycloak.util.eventForAppError
import com.github.hu553in.invites_keycloak.util.extractKeycloakException
import com.github.hu553in.invites_keycloak.util.keycloakStatusFrom
import com.github.hu553in.invites_keycloak.util.logger
import com.github.hu553in.invites_keycloak.util.withInviteContextInMdc
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.io.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.*

private const val INVITE_REDEEM_CHALLENGES_SESSION_KEY = "invite.redeem.challenges"
private const val INVITE_REDEEM_IN_FLIGHT_SESSION_KEY = "invite.redeem.in_flight"
private const val INVITE_REDEEM_CHALLENGE_TTL_MINUTES = 10L
private val INVITE_REDEEM_CHALLENGE_TTL: Duration = Duration.ofMinutes(INVITE_REDEEM_CHALLENGE_TTL_MINUTES)
private const val INVITE_REDEEM_CHALLENGE_MAX_PER_SESSION = 32

@Controller
@Validated
@RequestMapping("/invite")
@Tag(name = "Invite public API")
class InvitePublicController(
    private val inviteService: InviteService,
    private val keycloakAdminClient: KeycloakAdminClient,
    private val clock: Clock
) {

    private val log by logger()

    @GetMapping("/success")
    @Operation(summary = "Render invite success page")
    @Suppress("FunctionOnlyReturningConstant")
    fun redeemSuccess(): String = "public/account_created"

    @GetMapping("/{realm}/{token}", "/{realm}/{token}/")
    @Operation(summary = "Render invite confirmation page")
    fun redeemConfirmation(
        @PathVariable @NotBlank realm: String,
        @PathVariable @NotBlank token: String,
        model: Model,
        resp: HttpServletResponse,
        session: HttpSession
    ): String {
        val normalizedRealm = realm.trim()
        val normalizedToken = token.trim()
        log.atDebug()
            .addKeyValue(KEYCLOAK_REALM_KEY) { normalizedRealm }
            .addKeyValue(INVITE_TOKEN_LENGTH_KEY) { normalizedToken.length }
            .log { "Validating invite token" }
        val invite = inviteService.validateToken(normalizedRealm, normalizedToken)
        val inviteId = checkNotNull(invite.id) { "Null invite id is received from db for realm $realm" }
        val challenge = issueChallenge(session, inviteId, normalizedRealm)
            ?: return handleConfirmationInProgress(normalizedRealm, model, resp)

        resp.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, max-age=0")
        resp.setHeader(HttpHeaders.PRAGMA, "no-cache")
        resp.setHeader("X-Robots-Tag", "noindex, nofollow")

        model.addAttribute("realm", normalizedRealm)
        model.addAttribute("token", normalizedToken)
        model.addAttribute("redeemChallenge", challenge)
        return "public/invite_confirm"
    }

    @PostMapping("/{realm}/{token}", "/{realm}/{token}/")
    @Operation(summary = "Redeem invite")
    fun redeemInvite(
        @PathVariable @NotBlank realm: String,
        @PathVariable @NotBlank token: String,
        @RequestParam(name = "challenge", required = false) challenge: String?,
        model: Model,
        resp: HttpServletResponse,
        session: HttpSession
    ): String {
        val normalizedRealm = realm.trim()
        val normalizedToken = token.trim()
        val normalizedChallenge = challenge?.trim().orEmpty()

        if (normalizedChallenge.isBlank()) {
            return handleInvalidChallenge(
                normalizedRealm,
                "Invite redeem request is missing confirmation challenge",
                model,
                resp
            )
        }

        return redeemInviteWithChallenge(
            normalizedRealm = normalizedRealm,
            normalizedToken = normalizedToken,
            normalizedChallenge = normalizedChallenge,
            model = model,
            resp = resp,
            session = session
        )
    }

    private fun redeemInviteWithChallenge(
        normalizedRealm: String,
        normalizedToken: String,
        normalizedChallenge: String,
        model: Model,
        resp: HttpServletResponse,
        session: HttpSession
    ): String {
        val challengeInviteId = consumeChallengeAndMarkInFlight(session, normalizedChallenge, normalizedRealm)
            ?: return handleInvalidChallenge(
                normalizedRealm,
                "Invite redeem confirmation challenge is invalid or already used",
                model,
                resp
            )

        return try {
            redeemInviteWithConsumedChallenge(
                normalizedRealm = normalizedRealm,
                normalizedToken = normalizedToken,
                challengeInviteId = challengeInviteId,
                model = model,
                resp = resp
            )
        } finally {
            releaseRedeemInFlight(session, challengeInviteId, normalizedRealm)
        }
    }

    private fun redeemInviteWithConsumedChallenge(
        normalizedRealm: String,
        normalizedToken: String,
        challengeInviteId: UUID,
        model: Model,
        resp: HttpServletResponse
    ): String {
        val invite = inviteService.validateToken(normalizedRealm, normalizedToken)
        val inviteId = checkNotNull(invite.id) { "Null invite id is received from db for realm $normalizedRealm" }

        if (inviteId != challengeInviteId) {
            return handleInvalidChallenge(
                normalizedRealm,
                "Invite redeem confirmation challenge does not match invite",
                model,
                resp
            )
        }

        return withInviteContextInMdc(inviteId, normalizedRealm, invite.email) {
            val email = invite.email
            val userExists = keycloakAdminClient.userExists(normalizedRealm, email)

            if (userExists) {
                handleExistingUser(inviteId, model, resp)
            } else {
                redeemInvite(normalizedRealm, inviteId, email, invite.roles, model, resp)
            }
        }
    }

    private fun handleExistingUser(
        inviteId: UUID,
        model: Model,
        resp: HttpServletResponse
    ): String {
        log.atWarn()
            .log { "Invite target user already exists; revoking invite" }

        val revokeError = revokeInviteAndLogFailure(
            inviteId = inviteId,
            failureMessage = "Failed to revoke invite after detecting existing user"
        )

        if (revokeError != null) {
            model.addAttribute("error_message", ErrorMessages.SERVICE_TEMP_UNAVAILABLE)
            model.addAttribute("error_details", ErrorMessages.SERVICE_TEMP_UNAVAILABLE_DETAILS)
            resp.status = HttpStatus.SERVICE_UNAVAILABLE.value()
            return "generic_error"
        }

        model.addAttribute("error_message", ErrorMessages.ACCOUNT_ALREADY_EXISTS)
        model.addAttribute("error_details", ErrorMessages.ACCOUNT_ALREADY_EXISTS_DETAILS)
        resp.status = HttpStatus.CONFLICT.value()
        return "generic_error"
    }

    private fun redeemInvite(
        realm: String,
        inviteId: UUID,
        email: String,
        roles: Set<String>,
        model: Model,
        resp: HttpServletResponse
    ): String {
        var createdUserId: String? = null

        return runCatching {
            createdUserId = keycloakAdminClient.createUser(realm, email, email)
            if (roles.isNotEmpty()) {
                keycloakAdminClient.assignRealmRoles(realm, createdUserId, roles)
            }
            keycloakAdminClient.executeActionsEmail(realm, createdUserId)
            inviteService.useOnce(inviteId)

            log.atInfo()
                .addKeyValue(USER_ID_KEY) { createdUserId }
                .addKeyValue(INVITE_ROLES_KEY) { roles.joinToString(",") }
                .log { "Completed invite flow and triggered Keycloak actions" }
            "redirect:/invite/success"
        }.getOrElse { e ->
            rollbackUser(realm, createdUserId)
            val keycloakStatus = keycloakStatusFrom(e)
            val shouldRevoke = shouldRevokeInvite(e)
            val view = buildFailureView(shouldRevoke, inviteId, keycloakStatus, model, resp)

            log.dedupedEventForAppError(e, keycloakStatus = keycloakStatus)
                .addKeyValue(INVITE_FLOW_SHOULD_REVOKE_KEY) { shouldRevoke }
                .addKeyValue(REQUEST_STATUS_KEY) { resp.status }
                .setCause(e)
                .log { "Failed to complete invite flow; rolling back created user if any" }

            view
        }
    }

    private fun buildFailureView(
        shouldRevoke: Boolean,
        inviteId: UUID,
        keycloakStatus: HttpStatusCode?,
        model: Model,
        resp: HttpServletResponse
    ): String {
        if (shouldRevoke) {
            revokeInviteAndLogFailure(
                inviteId = inviteId,
                failureMessage = "Failed to revoke invite after invite flow error"
            )
            model.addAttribute("error_message", ErrorMessages.INVITE_NO_LONGER_VALID)
            model.addAttribute("error_details", ErrorMessages.INVITE_NO_LONGER_VALID_DETAILS)
            resp.status = HttpStatus.GONE.value()
            return "generic_error"
        }

        val responseStatus = when {
            keycloakStatus?.is4xxClientError == true -> keycloakStatus
            else -> HttpStatus.SERVICE_UNAVAILABLE
        }

        if (keycloakStatus?.is4xxClientError == true) {
            model.addAttribute("error_message", ErrorMessages.INVITE_CANNOT_BE_REDEEMED)
            model.addAttribute("error_details", ErrorMessages.INVITE_CANNOT_BE_REDEEMED_DETAILS)
        } else {
            model.addAttribute("error_message", ErrorMessages.SERVICE_TEMP_UNAVAILABLE)
            model.addAttribute("error_details", ErrorMessages.SERVICE_TEMP_UNAVAILABLE_DETAILS)
        }
        resp.status = responseStatus.value()
        return "generic_error"
    }

    private fun rollbackUser(realm: String, createdUserId: String?) {
        runCatching {
            if (createdUserId != null) {
                keycloakAdminClient.deleteUser(realm, createdUserId)
            }
        }.onFailure { deletionError ->
            log.eventForAppError(deletionError)
                .addKeyValue(USER_ID_KEY) { createdUserId }
                .setCause(deletionError)
                .log { "Failed to rollback Keycloak user after invite error (Keycloak client logged details)" }
        }
    }

    private fun revokeInviteAndLogFailure(inviteId: UUID, failureMessage: String): Throwable? {
        return runCatching { inviteService.revoke(inviteId, SYSTEM_USER_ID) }
            .onFailure { revokeError ->
                log.atError()
                    .setCause(revokeError)
                    .log { failureMessage }
            }
            .exceptionOrNull()
    }

    private fun shouldRevokeInvite(error: Throwable): Boolean {
        val status = keycloakStatusFrom(error)
        val keycloakEx = extractKeycloakException(error)
        val root = keycloakEx?.cause ?: error.cause ?: error

        return when {
            status?.is4xxClientError == true -> true
            status != null -> false
            root is InvalidInviteException ||
                root is InviteNotFoundException ||
                root is IllegalArgumentException ||
                root is IllegalStateException -> true

            else -> false
        }
    }

    private fun handleInvalidChallenge(
        realm: String,
        logMessage: String,
        model: Model,
        resp: HttpServletResponse
    ): String {
        log.atWarn()
            .addKeyValue(KEYCLOAK_REALM_KEY) { realm }
            .addKeyValue(REQUEST_STATUS_KEY) { HttpStatus.UNAUTHORIZED.value() }
            .addKeyValue(INVITE_INVALID_REASON_KEY) { "confirmation_invalid" }
            .log { logMessage }

        model.addAttribute("error_message", ErrorMessages.INVITE_CONFIRMATION_INVALID)
        model.addAttribute("error_details", ErrorMessages.INVITE_CONFIRMATION_INVALID_DETAILS)
        resp.status = HttpStatus.UNAUTHORIZED.value()
        return "generic_error"
    }

    private fun handleConfirmationInProgress(
        realm: String,
        model: Model,
        resp: HttpServletResponse
    ): String {
        log.atWarn()
            .addKeyValue(KEYCLOAK_REALM_KEY) { realm }
            .addKeyValue(REQUEST_STATUS_KEY) { HttpStatus.CONFLICT.value() }
            .addKeyValue(INVITE_INVALID_REASON_KEY) { "confirmation_in_progress" }
            .log { "Invite confirmation page requested while redeem is already in-flight for this invite in session" }

        model.addAttribute("error_message", ErrorMessages.INVITE_CONFIRMATION_INVALID)
        model.addAttribute("error_details", ErrorMessages.INVITE_CONFIRMATION_INVALID_DETAILS)
        resp.status = HttpStatus.CONFLICT.value()
        return "generic_error"
    }

    private fun issueChallenge(session: HttpSession, inviteId: UUID, realm: String): String? =
        session.redeemStateSync { challenges, inFlight, now ->
            cleanupExpiredChallenges(challenges, now)
            if (inFlight.contains(InviteRedeemInFlight(inviteId, realm))) {
                return@redeemStateSync null
            }
            removeChallengesForInvite(challenges, inviteId, realm)
            trimChallengesToCapacity(challenges)

            val challenge = UUID.randomUUID().toString()
            challenges[challenge] = InviteRedeemChallenge(
                inviteId = inviteId,
                realm = realm,
                expiresAt = now.plus(INVITE_REDEEM_CHALLENGE_TTL)
            )
            challenge
        }

    private fun consumeChallengeAndMarkInFlight(session: HttpSession, challenge: String, realm: String): UUID? =
        session.redeemStateSync { challenges, inFlight, now ->
            if (challenges.isEmpty()) {
                return@redeemStateSync null
            }

            cleanupExpiredChallenges(challenges, now)
            val data = challenges.remove(challenge)
            when {
                data == null -> null
                data.expiresAt.isBefore(now) -> null
                data.realm != realm -> null
                else -> {
                    if (!inFlight.add(InviteRedeemInFlight(data.inviteId, data.realm))) {
                        return@redeemStateSync null
                    }
                    removeChallengesForInvite(challenges, data.inviteId, data.realm)
                    data.inviteId
                }
            }
        }

    private fun releaseRedeemInFlight(session: HttpSession, inviteId: UUID, realm: String) {
        session.redeemStateSync { _, inFlight, _ -> inFlight.remove(InviteRedeemInFlight(inviteId, realm)) }
    }

    private fun cleanupExpiredChallenges(challenges: MutableMap<String, InviteRedeemChallenge>, now: Instant) {
        challenges.entries.removeIf { (_, challenge) -> challenge.expiresAt.isBefore(now) }
    }

    private fun removeChallengesForInvite(
        challenges: MutableMap<String, InviteRedeemChallenge>,
        inviteId: UUID,
        realm: String
    ) {
        challenges.entries.removeIf { (_, challenge) -> challenge.inviteId == inviteId && challenge.realm == realm }
    }

    private fun trimChallengesToCapacity(challenges: MutableMap<String, InviteRedeemChallenge>) {
        while (challenges.size >= INVITE_REDEEM_CHALLENGE_MAX_PER_SESSION) {
            val iter = challenges.entries.iterator()
            if (!iter.hasNext()) {
                return
            }
            val oldestKey = iter.next().key
            challenges.remove(oldestKey)
        }
    }

    private fun <T> HttpSession.redeemStateSync(
        mutate: (MutableMap<String, InviteRedeemChallenge>, MutableSet<InviteRedeemInFlight>, Instant) -> T
    ): T = synchronized(this) {
        val now = clock.instant()

        @Suppress("UNCHECKED_CAST")
        val challenges = getAttribute(INVITE_REDEEM_CHALLENGES_SESSION_KEY)
            as? MutableMap<String, InviteRedeemChallenge>
            ?: linkedMapOf()

        @Suppress("UNCHECKED_CAST")
        val inFlight = getAttribute(INVITE_REDEEM_IN_FLIGHT_SESSION_KEY)
            as? MutableSet<InviteRedeemInFlight>
            ?: linkedSetOf()

        val result = mutate(challenges, inFlight, now)
        if (challenges.isEmpty()) {
            removeAttribute(INVITE_REDEEM_CHALLENGES_SESSION_KEY)
        } else {
            setAttribute(INVITE_REDEEM_CHALLENGES_SESSION_KEY, challenges)
        }
        if (inFlight.isEmpty()) {
            removeAttribute(INVITE_REDEEM_IN_FLIGHT_SESSION_KEY)
        } else {
            setAttribute(INVITE_REDEEM_IN_FLIGHT_SESSION_KEY, inFlight)
        }
        result
    }

    private data class InviteRedeemChallenge(
        val inviteId: UUID,
        val realm: String,
        val expiresAt: Instant
    ) : Serializable {
        private companion object {
            private const val serialVersionUID = 1L
        }
    }

    private data class InviteRedeemInFlight(
        val inviteId: UUID,
        val realm: String
    ) : Serializable {
        private companion object {
            private const val serialVersionUID = 1L
        }
    }
}
