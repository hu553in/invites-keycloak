package com.github.hu553in.invites_keycloak.controller

import com.github.hu553in.invites_keycloak.client.KeycloakAdminClient
import com.github.hu553in.invites_keycloak.service.InviteService
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

@Controller
@Validated
@RequestMapping("/invite")
@Tag(name = "Invite public API")
class InvitePublicController(
    private val inviteService: InviteService,
    private val keycloakAdminClient: KeycloakAdminClient
) {

    @GetMapping("/{realm}/{token}")
    @Operation(summary = "Validate invite")
    fun validateInvite(
        @PathVariable @NotBlank realm: String,
        @PathVariable @NotBlank token: String,
        model: Model,
        resp: HttpServletResponse
    ): String {
        val invite = inviteService.validateToken(realm, token)
        val inviteId = checkNotNull(invite.id) { "Null invite id is received from db for realm $realm" }

        val email = invite.email
        if (keycloakAdminClient.userExists(realm, email)) {
            model.addAttribute("error_message", "Account already exists")
            model.addAttribute(
                "error_details",
                "An account with passed data is already registered. " +
                    "If you believe this is an error, please contact your administrator."
            )
            resp.status = HttpStatus.CONFLICT.value()
            return "public/generic_error"
        }

        val userId = keycloakAdminClient.createUser(realm, email, email)
        keycloakAdminClient.assignRealmRoles(realm, userId, invite.roles)
        keycloakAdminClient.executeActionsEmail(realm, userId)
        inviteService.useOnce(inviteId)

        return "public/account_created"
    }
}
