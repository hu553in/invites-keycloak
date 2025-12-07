package com.github.hu553in.invites_keycloak.controller

import com.github.hu553in.invites_keycloak.client.KeycloakAdminClient
import com.github.hu553in.invites_keycloak.exception.KeycloakAdminClientException
import com.github.hu553in.invites_keycloak.service.InviteService
import com.github.hu553in.invites_keycloak.util.ErrorMessages
import com.github.hu553in.invites_keycloak.util.logger
import com.github.hu553in.invites_keycloak.util.maskSensitive
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.reactive.function.client.WebClientResponseException
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
        val invite = inviteService.validateToken(normalizedRealm, normalizedToken)
        val inviteId = checkNotNull(invite.id) { "Null invite id is received from db for realm $realm" }

        val email = invite.email
        if (keycloakAdminClient.userExists(normalizedRealm, email)) {
            return handleExistingUser(normalizedRealm, inviteId, email, model, resp)
        }

        return completeInvite(normalizedRealm, inviteId, email, invite.roles, model, resp)
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

        runCatching { inviteService.revoke(inviteId) }
            .onFailure {
                log.atError()
                    .addKeyValue("invite.id") { inviteId }
                    .addKeyValue("realm") { realm }
                    .addKeyValue("email") { maskSensitive(email) }
                    .setCause(it)
                    .log { "Failed to revoke invite after detecting existing user" }
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

            "public/account_created"
        }.getOrElse { e ->
            rollbackUser(realm, inviteId, email, createdUserId)
            val shouldRevoke = shouldRevokeInvite(e)
            val view = if (shouldRevoke) {
                runCatching { inviteService.revoke(inviteId) }
                    .onFailure { revokeError ->
                        log.atError()
                            .addKeyValue("invite.id") { inviteId }
                            .addKeyValue("realm") { realm }
                            .addKeyValue("email") { maskSensitive(email) }
                            .setCause(revokeError)
                            .log { "Failed to revoke invite after invite flow error" }
                    }
                model.addAttribute(
                    "error_message",
                    ErrorMessages.INVITE_NO_LONGER_VALID
                )
                model.addAttribute(
                    "error_details",
                    ErrorMessages.INVITE_NO_LONGER_VALID_DETAILS
                )
                resp.status = HttpStatus.SERVICE_UNAVAILABLE.value()
                "generic_error"
            } else {
                model.addAttribute(
                    "error_message",
                    ErrorMessages.SERVICE_TEMP_UNAVAILABLE
                )
                model.addAttribute(
                    "error_details",
                    ErrorMessages.SERVICE_TEMP_UNAVAILABLE_DETAILS
                )
                resp.status = HttpStatus.SERVICE_UNAVAILABLE.value()
                "generic_error"
            }

            log.atError()
                .addKeyValue("invite.id") { inviteId }
                .addKeyValue("realm") { realm }
                .addKeyValue("email") { maskSensitive(email) }
                .setCause(e)
                .log { "Failed to complete invite flow; rolling back created user if any" }

            view
        }
    }

    private fun rollbackUser(realm: String, inviteId: UUID, email: String, createdUserId: String?) {
        runCatching {
            if (createdUserId != null) {
                keycloakAdminClient.deleteUser(realm, createdUserId)
            }
        }.onFailure { deletionError ->
            log.atError()
                .addKeyValue("invite.id") { inviteId }
                .addKeyValue("realm") { realm }
                .addKeyValue("email") { maskSensitive(email) }
                .setCause(deletionError)
                .log { "Failed to rollback Keycloak user after invite error" }
        }
    }

    private fun shouldRevokeInvite(error: Throwable): Boolean {
        val keycloakEx = extractKeycloakException(error)
        val status = keycloakEx?.statusCode ?: (keycloakEx?.cause as? WebClientResponseException)?.statusCode
        val root = keycloakEx?.cause ?: error.cause ?: error
        val rootStatus = (root as? WebClientResponseException)?.statusCode

        return when {
            status?.is4xxClientError == true -> true
            rootStatus?.is4xxClientError == true -> true
            status?.is5xxServerError == true -> false
            rootStatus?.is5xxServerError == true -> false
            else -> root is IllegalArgumentException || root is IllegalStateException
        }
    }

    private fun extractKeycloakException(error: Throwable): KeycloakAdminClientException? {
        return when (error) {
            is KeycloakAdminClientException -> error
            else -> error.cause as? KeycloakAdminClientException
        }
    }
}
