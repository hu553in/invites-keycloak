package com.github.hu553in.invites_keycloak.features.invite.web

import com.github.hu553in.invites_keycloak.features.invite.core.service.InviteService
import com.github.hu553in.invites_keycloak.features.keycloak.core.service.KeycloakAdminClient
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.NotBlank
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Validated
@RequestMapping("/invite")
@Tag(name = "Invite public API")
class InvitePublicController(
    private val inviteService: InviteService,
    private val keycloakAdminClient: KeycloakAdminClient
) {

    @GetMapping("/{realm}/{token}")
    @Operation(summary = "Validate invite token for realm")
    fun validateToken(
        @PathVariable @NotBlank realm: String,
        @PathVariable @NotBlank token: String
    ) {
        val invite = inviteService.validateToken(realm, token)
        val email = invite.email
        val exists = keycloakAdminClient.userExists(realm, email)
        if (exists) {
            TODO("Error view")
        }
        val userId = keycloakAdminClient.createUser(realm, email, email)
        keycloakAdminClient.assignRealmRoles(realm, userId, invite.roles)
        keycloakAdminClient.executeActionsEmail(realm, userId)
        inviteService.useOnce(invite.id!!)
        TODO("Success view")
    }
}
