package com.github.hu553in.invites_keycloak.controller

import com.github.hu553in.invites_keycloak.client.KeycloakAdminClient
import com.github.hu553in.invites_keycloak.config.props.InviteProps
import com.github.hu553in.invites_keycloak.controller.InviteAdminFormSupport.RoleFetchResult
import com.github.hu553in.invites_keycloak.controller.InviteAdminFormSupport.addExpiryMetadata
import com.github.hu553in.invites_keycloak.controller.InviteAdminFormSupport.configuredRoles
import com.github.hu553in.invites_keycloak.controller.InviteAdminFormSupport.ensureFormPresent
import com.github.hu553in.invites_keycloak.controller.InviteAdminFormSupport.nameOrSystem
import com.github.hu553in.invites_keycloak.controller.InviteAdminFormSupport.populateFormMetadata
import com.github.hu553in.invites_keycloak.controller.InviteAdminFormSupport.resolveRealmOrDefault
import com.github.hu553in.invites_keycloak.controller.InviteAdminFormSupport.rolesForView
import com.github.hu553in.invites_keycloak.controller.InviteAdminFormSupport.validateExpiryMinutes
import com.github.hu553in.invites_keycloak.controller.InviteAdminFormSupport.validateRealm
import com.github.hu553in.invites_keycloak.controller.InviteAdminMappings.buildInviteLink
import com.github.hu553in.invites_keycloak.controller.InviteAdminMappings.toView
import com.github.hu553in.invites_keycloak.exception.ActiveInviteExistsException
import com.github.hu553in.invites_keycloak.service.InviteService
import com.github.hu553in.invites_keycloak.service.MailService
import com.github.hu553in.invites_keycloak.util.logger
import com.github.hu553in.invites_keycloak.util.maskSensitive
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.Clock
import java.time.Duration
import java.util.*

@Controller
@RequestMapping("/admin/invite")
@Validated
class InviteAdminController(
    private val inviteService: InviteService,
    private val mailService: MailService,
    private val inviteProps: InviteProps,
    private val keycloakAdminClient: KeycloakAdminClient,
    private val clock: Clock
) {

    private val log by logger()

    @GetMapping
    fun listInvites(model: Model): String {
        val now = clock.instant()
        model.addAttribute("invites", inviteService.listInvites().map { it.toView(now) })
        addExpiryMetadata(model, inviteProps)
        return "admin/invite/list"
    }

    @GetMapping("/new")
    fun newInviteForm(
        @RequestParam(required = false) realm: String?,
        model: Model
    ): String {
        val selectedRealm = resolveRealmOrDefault(realm, inviteProps)
        prepareForm(model, selectedRealm)
        return "admin/invite/new"
    }

    @PostMapping
    fun createInvite(
        @ModelAttribute("inviteForm") @Valid inviteForm: InviteForm,
        bindingResult: BindingResult,
        model: Model,
        redirectAttributes: RedirectAttributes,
        authentication: Authentication?
    ): String {
        val validatedRealm = validateRealm(inviteForm.realm, inviteProps, bindingResult)
        val expiryDuration = validateExpiryMinutes(inviteForm.expiryMinutes, inviteProps, bindingResult)
        val rolesToUse = validatedRealm?.let { resolveRolesForSubmission(it, inviteForm.roles, bindingResult) }
        val hasMissingInputs = listOf(validatedRealm, expiryDuration, rolesToUse).any { it == null }

        val result = if (bindingResult.hasErrors() || hasMissingInputs) {
            val realmForForm = validatedRealm ?: resolveRealmOrDefault(inviteForm.realm, inviteProps)
            inviteForm.realm = realmForForm
            if (inviteForm.roles.isEmpty()) {
                inviteForm.roles.addAll(configuredRoles(realmForForm, inviteProps))
            }
            prepareForm(model, realmForForm)
            "admin/invite/new"
        } else {
            try {
                val created = inviteService.createInvite(
                    realm = validatedRealm!!,
                    email = inviteForm.email,
                    expiresAt = clock.instant().plus(expiryDuration!!),
                    maxUses = inviteForm.maxUses,
                    roles = rolesToUse!!,
                    createdBy = authentication.nameOrSystem()
                )
                val link = buildInviteLink(inviteProps, validatedRealm, created.rawToken)
                val mailStatus = sendMail(created, link)

                redirectAttributes.addFlashAttribute("successMessage", "Invite created for ${created.invite.email}")
                redirectAttributes.addFlashAttribute("inviteLink", link)
                applyMailFlash(mailStatus, created.invite.email, redirectAttributes)
                "redirect:/admin/invite"
            } catch (e: ActiveInviteExistsException) {
                bindingResult.rejectValue("email", "email.duplicate", e.message ?: "Active invite already exists")
                val realmForRetry = resolveRealmOrDefault(inviteForm.realm, inviteProps)
                inviteForm.realm = realmForRetry
                prepareForm(model, realmForRetry)
                "admin/invite/new"
            } catch (
                @Suppress("TooGenericExceptionCaught")
                e: Exception
            ) {
                log.atError()
                    .addKeyValue("realm") { inviteForm.realm }
                    .addKeyValue("email") { maskSensitive(inviteForm.email) }
                    .setCause(e)
                    .log { "Failed to create invite" }
                bindingResult.reject("createInvite", e.message ?: "Unable to create invite")
                val realmForRetry = resolveRealmOrDefault(inviteForm.realm, inviteProps)
                inviteForm.realm = realmForRetry
                prepareForm(model, realmForRetry)
                "admin/invite/new"
            }
        }

        return result
    }

    @PostMapping("/{id}/revoke")
    fun revokeInvite(
        @PathVariable id: UUID,
        redirectAttributes: RedirectAttributes,
        authentication: Authentication?
    ): String {
        return runCatching {
            val invite = inviteService.get(id)
            inviteService.revoke(id, authentication.nameOrSystem())
            redirectAttributes.addFlashAttribute("successMessage", "Invite revoked for ${invite.email}")
            "redirect:/admin/invite"
        }.getOrElse {
            log.atWarn()
                .addKeyValue("invite.id") { id }
                .setCause(it)
                .log { "Failed to revoke invite" }
            redirectAttributes.addFlashAttribute("errorMessage", it.message ?: "Failed to revoke invite")
            "redirect:/admin/invite"
        }
    }

    @PostMapping("/{id}/delete")
    fun deleteInvite(
        @PathVariable id: UUID,
        redirectAttributes: RedirectAttributes
    ): String {
        return runCatching {
            val deleted = inviteService.delete(id)
            redirectAttributes.addFlashAttribute("successMessage", "Invite deleted for ${deleted.email}")
            "redirect:/admin/invite"
        }.getOrElse {
            log.atWarn()
                .addKeyValue("invite.id") { id }
                .setCause(it)
                .log { "Failed to delete invite" }
            redirectAttributes.addFlashAttribute("errorMessage", it.message ?: "Failed to delete invite")
            "redirect:/admin/invite"
        }
    }

    @PostMapping("/{id}/resend")
    fun resendInvite(
        @PathVariable id: UUID,
        @RequestParam("expiryMinutes") expiryMinutes: Long?,
        redirectAttributes: RedirectAttributes,
        authentication: Authentication?
    ): String {
        val expiryDuration = validateExpiryMinutes(expiryMinutes, inviteProps)
        if (expiryDuration == null) {
            redirectAttributes.addFlashAttribute(
                "errorMessage",
                "Expiry minutes must be between ${inviteProps.expiry.min.toMinutes()} and " +
                    "${inviteProps.expiry.max.toMinutes()}."
            )
            return "redirect:/admin/invite"
        }

        return resendInviteInternal(id, expiryDuration, redirectAttributes, authentication)
    }

    private fun resendInviteInternal(
        id: UUID,
        expiryDuration: Duration,
        redirectAttributes: RedirectAttributes,
        authentication: Authentication?
    ): String {
        return runCatching {
            val invite = inviteService.get(id)

            val allowedRoles = fetchAllowedRolesForResend(id, invite.realm, invite.roles, redirectAttributes)
                ?: return@runCatching "redirect:/admin/invite"

            if (invite.roles.isNotEmpty() && !allowedRoles.containsAll(invite.roles)) {
                return@runCatching refuseResendDueToMissingRoles(id, invite.realm, redirectAttributes)
            }

            val created = inviteService.resendInvite(
                inviteId = id,
                expiresAt = clock.instant().plus(expiryDuration),
                createdBy = authentication.nameOrSystem()
            )
            val realm = created.invite.realm
            val link = buildInviteLink(inviteProps, realm, created.rawToken)
            val mailStatus = sendMail(created, link)

            redirectAttributes.addFlashAttribute("successMessage", "Invite resent to ${created.invite.email}")
            redirectAttributes.addFlashAttribute("inviteLink", link)
            applyMailFlash(mailStatus, created.invite.email, redirectAttributes)
            "redirect:/admin/invite"
        }.getOrElse {
            log.atWarn()
                .addKeyValue("invite.id") { id }
                .setCause(it)
                .log { "Failed to resend invite" }
            redirectAttributes.addFlashAttribute("errorMessage", it.message ?: "Failed to resend invite")
            "redirect:/admin/invite"
        }
    }

    private fun fetchAllowedRolesForResend(
        inviteId: UUID,
        realm: String,
        roles: Set<String>,
        redirectAttributes: RedirectAttributes
    ): Set<String>? {
        if (roles.isEmpty()) {
            return emptySet()
        }
        return runCatching { keycloakAdminClient.listRealmRoles(realm).toSet() }
            .onFailure {
                log.atWarn()
                    .addKeyValue("invite.id") { inviteId }
                    .addKeyValue("realm") { realm }
                    .setCause(it)
                    .log { "Failed to fetch realm roles before resend" }
                redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Cannot resend invite now: roles are unavailable (Keycloak may be down)."
                )
            }
            .getOrNull()
    }

    private fun refuseResendDueToMissingRoles(
        inviteId: UUID,
        realm: String,
        redirectAttributes: RedirectAttributes
    ): String {
        log.atWarn()
            .addKeyValue("invite.id") { inviteId }
            .addKeyValue("realm") { realm }
            .log { "Refusing to resend invite because some roles are missing in Keycloak" }
        redirectAttributes.addFlashAttribute(
            "errorMessage",
            "Cannot resend: invite roles no longer exist in Keycloak. Create a new invite with valid roles."
        )
        return "redirect:/admin/invite"
    }

    private fun sendMail(created: InviteService.CreatedInvite, link: String): MailService.MailSendStatus {
        return mailService.sendInviteEmail(
            MailService.InviteMailData(
                email = created.invite.email,
                target = created.invite.realm,
                link = link,
                expiresAt = created.invite.expiresAt
            )
        )
    }

    private fun applyMailFlash(
        status: MailService.MailSendStatus,
        email: String,
        redirectAttributes: RedirectAttributes
    ) {
        val (message, severity) = when (status) {
            MailService.MailSendStatus.OK -> "Invite email sent to $email" to "info"
            MailService.MailSendStatus.NOT_CONFIGURED ->
                "Invite email not sent: SMTP is not configured." to "warning"

            MailService.MailSendStatus.FAIL ->
                "Invite email could not be sent. Please check the mail logs." to "error"
        }
        redirectAttributes.addFlashAttribute("mailStatusMessage", message)
        redirectAttributes.addFlashAttribute("mailStatusLevel", severity)
    }

    private fun prepareForm(model: Model, realm: String) {
        val configuredRoles = configuredRoles(realm, inviteProps)
        val rolesVisible = configuredRoles.isNotEmpty()
        val roleFetch = if (rolesVisible) {
            fetchRoles(realm)
        } else {
            RoleFetchResult(emptyList(), null, available = true)
        }
        val roleOptions = if (rolesVisible) {
            rolesForView(realm, inviteProps, roleFetch)
        } else {
            emptyList()
        }
        val rolesAvailable = if (rolesVisible) {
            roleFetch.available
        } else {
            true
        }
        ensureFormPresent(
            model,
            realm,
            inviteProps,
            rolesAvailable = rolesAvailable,
            allowedRoles = roleOptions.toSet()
        )
        if (rolesVisible && !roleFetch.available) {
            val form = model.getAttribute("inviteForm") as? InviteForm
            form?.roles?.clear()
        }
        populateFormMetadata(model, realm, inviteProps, roleFetch, roleOptions, rolesVisible, rolesAvailable)
    }

    private fun fetchRoles(realm: String): RoleFetchResult {
        return runCatching {
            val roles = keycloakAdminClient.listRealmRoles(realm)
            RoleFetchResult(roles, null, available = true)
        }.getOrElse {
            log.atWarn()
                .addKeyValue("realm") { realm }
                .setCause(it)
                .log { "Failed to fetch roles for realm" }
            RoleFetchResult(
                roles = emptyList(),
                errorMessage = "Roles are temporarily unavailable. Keycloak may be down; please retry later.",
                available = false
            )
        }
    }

    private fun resolveRolesForSubmission(
        realm: String,
        requestedRoles: Set<String>,
        bindingResult: BindingResult
    ): Set<String>? {
        val sanitizedRoles = requestedRoles
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toCollection(LinkedHashSet())

        val rolesToUse = if (sanitizedRoles.isEmpty()) {
            configuredRoles(realm, inviteProps)
        } else {
            sanitizedRoles
        }
        if (rolesToUse.isEmpty()) {
            return rolesToUse.takeUnless { bindingResult.hasErrors() }
        }

        val allowedRoles = runCatching { keycloakAdminClient.listRealmRoles(realm).toSet() }
            .onFailure {
                log.atWarn()
                    .addKeyValue("realm") { realm }
                    .setCause(it)
                    .log { "Failed to fetch roles for validation" }
                bindingResult.reject(
                    "roles.unavailable",
                    "Unable to fetch realm roles from Keycloak right now. Please try again."
                )
            }
            .getOrNull()

        if (allowedRoles != null && !allowedRoles.containsAll(rolesToUse)) {
            bindingResult.rejectValue("roles", "roles.invalid", "Selected roles are not available in Keycloak")
        }

        return rolesToUse.takeUnless { bindingResult.hasErrors() }
    }

    data class InviteForm(
        @field:NotBlank
        var realm: String = "",
        @field:NotBlank
        @field:Email
        var email: String = "",
        @field:Positive
        var expiryMinutes: Long = 0,
        @field:Min(1)
        var maxUses: Int = 1,
        var roles: MutableSet<String> = linkedSetOf()
    )
}
