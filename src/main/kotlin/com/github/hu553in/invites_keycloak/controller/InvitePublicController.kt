package com.github.hu553in.invites_keycloak.controller

import com.github.hu553in.invites_keycloak.client.KeycloakAdminClient
import com.github.hu553in.invites_keycloak.exception.InvalidInviteException
import com.github.hu553in.invites_keycloak.exception.InviteNotFoundException
import com.github.hu553in.invites_keycloak.service.InviteService
import com.github.hu553in.invites_keycloak.util.ErrorMessages
import com.github.hu553in.invites_keycloak.util.SYSTEM_USER_ID
import com.github.hu553in.invites_keycloak.util.eventForInviteError
import com.github.hu553in.invites_keycloak.util.extractKeycloakException
import com.github.hu553in.invites_keycloak.util.keycloakStatusFrom
import com.github.hu553in.invites_keycloak.util.logger
import com.github.hu553in.invites_keycloak.util.maskSensitive
import com.github.hu553in.invites_keycloak.util.withInviteContextInMdc
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import java.util.*

@Controller
@Validated
@RequestMapping("/invite")
@Tag(name = "Invite public API")
class InvitePublicController(
    private val inviteService: InviteService,
    private val keycloakAdminClient: KeycloakAdminClient
) {

    private val log by logger()

    @GetMapping("/{realm}/{token}")
    @Operation(summary = "Validate invite")
    fun validateInvite(
        @PathVariable @NotBlank realm: String,
        @PathVariable @NotBlank token: String,
        model: Model,
        resp: HttpServletResponse
    ): String {
        val normalizedRealm = realm.trim()
        val normalizedToken = token.trim()
        log.atDebug()
            .addKeyValue("realm") { normalizedRealm }
            .addKeyValue("token_length") { normalizedToken.length }
            .log { "Validating invite token" }
        val invite = inviteService.validateToken(normalizedRealm, normalizedToken)
        val inviteId = checkNotNull(invite.id) { "Null invite id is received from db for realm $realm" }
        return withInviteContextInMdc(inviteId, normalizedRealm, invite.email) {
            val email = invite.email
            val userExists = keycloakAdminClient.userExists(normalizedRealm, email)

            if (userExists) {
                return@withInviteContextInMdc handleExistingUser(normalizedRealm, inviteId, email, model, resp)
            }

            completeInvite(normalizedRealm, inviteId, email, invite.roles, model, resp)
        }
    }

    private fun handleExistingUser(
        realm: String,
        inviteId: UUID,
        email: String,
        model: Model,
        resp: HttpServletResponse
    ): String {
        log.atWarn()
            .addKeyValue("invite.id") { inviteId }
            .addKeyValue("realm") { realm }
            .addKeyValue("email") { maskSensitive(email) }
            .log { "Invite target user already exists; revoking invite" }

        val revokeError = runCatching { inviteService.revoke(inviteId, SYSTEM_USER_ID) }
            .onFailure {
                log.atError()
                    .addKeyValue("invite.id") { inviteId }
                    .addKeyValue("realm") { realm }
                    .addKeyValue("email") { maskSensitive(email) }
                    .setCause(it)
                    .log { "Failed to revoke invite after detecting existing user" }
            }
            .exceptionOrNull()

        if (revokeError != null) {
            model.addAttribute("error_message", ErrorMessages.SERVICE_TEMP_UNAVAILABLE)
            model.addAttribute("error_details", ErrorMessages.SERVICE_TEMP_UNAVAILABLE_DETAILS)
            resp.status = HttpStatus.SERVICE_UNAVAILABLE.value()
            return "generic_error"
        }

        model.addAttribute("error_message", "Account already exists")
        model.addAttribute(
            "error_details",
            "An account with passed data is already registered. " +
                "If you believe this is an error, please contact your administrator."
        )
        resp.status = HttpStatus.CONFLICT.value()
        return "generic_error"
    }

    private fun completeInvite(
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
                .addKeyValue("invite.id") { inviteId }
                .addKeyValue("realm") { realm }
                .addKeyValue("email") { maskSensitive(email) }
                .addKeyValue("user.id") { createdUserId }
                .addKeyValue("roles") { roles.joinToString(",") }
                .log { "Completed invite flow and triggered Keycloak actions" }
            "public/account_created"
        }.getOrElse { e ->
            rollbackUser(realm, inviteId, email, createdUserId)
            val keycloakStatus = keycloakStatusFrom(e)
            val shouldRevoke = shouldRevokeInvite(e)
            val view = buildFailureView(shouldRevoke, inviteId, realm, email, keycloakStatus, model, resp)

            log.eventForInviteError(e, keycloakStatus = keycloakStatus, deduplicateKeycloak = true)
                .addKeyValue("invite.id") { inviteId }
                .addKeyValue("realm") { realm }
                .addKeyValue("email") { maskSensitive(email) }
                .setCause(e)
                .log { "Failed to complete invite flow; rolling back created user if any" }

            view
        }
    }

    private fun buildFailureView(
        shouldRevoke: Boolean,
        inviteId: UUID,
        realm: String,
        email: String,
        keycloakStatus: HttpStatusCode?,
        model: Model,
        resp: HttpServletResponse
    ): String {
        if (shouldRevoke) {
            runCatching { inviteService.revoke(inviteId, SYSTEM_USER_ID) }
                .onFailure { revokeError ->
                    log.atError()
                        .addKeyValue("invite.id") { inviteId }
                        .addKeyValue("realm") { realm }
                        .addKeyValue("email") { maskSensitive(email) }
                        .setCause(revokeError)
                        .log { "Failed to revoke invite after invite flow error" }
                }
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
            model.addAttribute("error_message", ErrorMessages.INVITE_CANNOT_BE_COMPLETED)
            model.addAttribute("error_details", ErrorMessages.INVITE_CANNOT_BE_COMPLETED_DETAILS)
        } else {
            model.addAttribute("error_message", ErrorMessages.SERVICE_TEMP_UNAVAILABLE)
            model.addAttribute("error_details", ErrorMessages.SERVICE_TEMP_UNAVAILABLE_DETAILS)
        }
        resp.status = responseStatus.value()
        return "generic_error"
    }

    private fun rollbackUser(realm: String, inviteId: UUID, email: String, createdUserId: String?) {
        runCatching {
            if (createdUserId != null) {
                keycloakAdminClient.deleteUser(realm, createdUserId)
            }
        }.onFailure { deletionError ->
            log.atDebug()
                .addKeyValue("invite.id") { inviteId }
                .addKeyValue("realm") { realm }
                .addKeyValue("email") { maskSensitive(email) }
                .setCause(deletionError)
                .log { "Failed to rollback Keycloak user after invite error (Keycloak client logged details)" }
        }
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
}
