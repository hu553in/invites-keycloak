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
import com.github.hu553in.invites_keycloak.util.ERROR_COUNT_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_EMAIL_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_EXPIRY_MINUTES_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_ID_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_REALM_KEY
import com.github.hu553in.invites_keycloak.util.dedupedEventForInviteError
import com.github.hu553in.invites_keycloak.util.isClientSideInviteFailure
import com.github.hu553in.invites_keycloak.util.logger
import com.github.hu553in.invites_keycloak.util.maskSensitive
import com.github.hu553in.invites_keycloak.util.withInviteContextInMdc
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
        log.atDebug().log { "Rendering admin invite list" }
        return "admin/invite/list"
    }

    @GetMapping("/new")
    fun newInviteForm(
        @RequestParam(required = false) realm: String?,
        model: Model
    ): String {
        val selectedRealm = resolveRealmOrDefault(realm, inviteProps)
        prepareForm(model, selectedRealm)
        log.atDebug()
            .addKeyValue(INVITE_REALM_KEY) { selectedRealm }
            .log { "Rendering admin invite form" }
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

        if (bindingResult.hasErrors() || hasMissingInputs) {
            return handleCreateInviteValidationFailure(inviteForm, model, validatedRealm, bindingResult)
        }

        return try {
            createInviteAndRedirect(
                realm = validatedRealm!!,
                expiryDuration = expiryDuration!!,
                rolesToUse = rolesToUse!!,
                inviteForm = inviteForm,
                redirectAttributes = redirectAttributes,
                authentication = authentication
            )
        } catch (e: ActiveInviteExistsException) {
            handleDuplicateInvite(e, inviteForm, bindingResult, model)
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception
        ) {
            handleCreateInviteError(e, inviteForm, bindingResult, model)
        }
    }

    private fun handleCreateInviteValidationFailure(
        inviteForm: InviteForm,
        model: Model,
        validatedRealm: String?,
        bindingResult: BindingResult
    ): String {
        val realmForForm = validatedRealm ?: resolveRealmOrDefault(inviteForm.realm, inviteProps)
        inviteForm.realm = realmForForm
        if (inviteForm.roles.isEmpty()) {
            inviteForm.roles.addAll(configuredRoles(realmForForm, inviteProps))
        }
        log.atDebug()
            .addKeyValue(INVITE_REALM_KEY) { realmForForm }
            .addKeyValue(INVITE_EMAIL_KEY) { maskSensitive(inviteForm.email) }
            .addKeyValue(ERROR_COUNT_KEY) { bindingResult.errorCount }
            .log { "Invite form validation failed; re-rendering form" }
        prepareForm(model, realmForForm)
        return "admin/invite/new"
    }

    private fun createInviteAndRedirect(
        realm: String,
        expiryDuration: Duration,
        rolesToUse: Set<String>,
        inviteForm: InviteForm,
        redirectAttributes: RedirectAttributes,
        authentication: Authentication?
    ): String {
        val createdBy = authentication.nameOrSystem()
        val created = inviteService.createInvite(
            realm = realm,
            email = inviteForm.email,
            expiresAt = clock.instant().plus(expiryDuration),
            maxUses = inviteForm.maxUses,
            roles = rolesToUse,
            createdBy = createdBy
        )

        val link = buildInviteLink(inviteProps, realm, created.rawToken)
        redirectAttributes.addFlashAttribute("successMessage", "Invite created for ${created.invite.email}")
        redirectAttributes.addFlashAttribute("inviteLink", link)
        applyMailFlash(sendMail(created, link), created.invite.email, redirectAttributes)
        return "redirect:/admin/invite"
    }

    private fun handleDuplicateInvite(
        e: ActiveInviteExistsException,
        inviteForm: InviteForm,
        bindingResult: BindingResult,
        model: Model
    ): String {
        log.atWarn()
            .addKeyValue(INVITE_REALM_KEY) { inviteForm.realm }
            .addKeyValue(INVITE_EMAIL_KEY) { maskSensitive(inviteForm.email) }
            .setCause(e)
            .log { "Active invite already exists" }
        bindingResult.rejectValue("email", "email.duplicate", e.message ?: "Active invite already exists")
        val realmForRetry = resolveRealmOrDefault(inviteForm.realm, inviteProps)
        inviteForm.realm = realmForRetry
        prepareForm(model, realmForRetry)
        return "admin/invite/new"
    }

    private fun handleCreateInviteError(
        e: Exception,
        inviteForm: InviteForm,
        bindingResult: BindingResult,
        model: Model
    ): String {
        log.dedupedEventForInviteError(e)
            .addKeyValue(INVITE_REALM_KEY) { inviteForm.realm }
            .addKeyValue(INVITE_EMAIL_KEY) { maskSensitive(inviteForm.email) }
            .setCause(e)
            .log { "Failed to create invite" }
        val errorMessage = if (e.isClientSideInviteFailure()) {
            "Unable to create invite; please check input and try again."
        } else {
            "Unable to create invite due to server error. Please retry later."
        }
        bindingResult.reject("createInvite", errorMessage)
        val realmForRetry = resolveRealmOrDefault(inviteForm.realm, inviteProps)
        inviteForm.realm = realmForRetry
        prepareForm(model, realmForRetry)
        return "admin/invite/new"
    }

    @PostMapping("/{id}/revoke")
    fun revokeInvite(
        @PathVariable id: UUID,
        redirectAttributes: RedirectAttributes,
        authentication: Authentication?
    ): String {
        var inviteContext: Pair<String, String>? = null
        return runCatching {
            val invite = inviteService.get(id).also {
                inviteContext = it.realm to it.email
            }
            withInviteContextInMdc(id, invite.realm, invite.email) {
                inviteService.revoke(id, authentication.nameOrSystem())
                redirectAttributes.addFlashAttribute("successMessage", "Invite revoked for ${invite.email}")
            }
            "redirect:/admin/invite"
        }.getOrElse {
            log.dedupedEventForInviteError(it)
                .addKeyValue(INVITE_ID_KEY) { id }
                .apply {
                    inviteContext?.let { ctx ->
                        addKeyValue(INVITE_REALM_KEY) { ctx.first }
                        addKeyValue(INVITE_EMAIL_KEY) { maskSensitive(ctx.second) }
                    }
                }
                .setCause(it)
                .log { "Failed to revoke invite" }
            redirectAttributes.addFlashAttribute("errorMessage", it.message ?: "Failed to revoke invite")
            "redirect:/admin/invite"
        }
    }

    @PostMapping("/{id}/delete")
    fun deleteInvite(
        @PathVariable id: UUID,
        redirectAttributes: RedirectAttributes,
        authentication: Authentication?
    ): String {
        var inviteContext: Pair<String, String>? = null
        return runCatching {
            val deleted = inviteService.delete(id, authentication.nameOrSystem()).also {
                inviteContext = it.realm to it.email
            }
            withInviteContextInMdc(id, deleted.realm, deleted.email) {
                redirectAttributes.addFlashAttribute("successMessage", "Invite deleted for ${deleted.email}")
            }
            "redirect:/admin/invite"
        }.getOrElse {
            log.dedupedEventForInviteError(it)
                .addKeyValue(INVITE_ID_KEY) { id }
                .apply {
                    inviteContext?.let { ctx ->
                        addKeyValue(INVITE_REALM_KEY) { ctx.first }
                        addKeyValue(INVITE_EMAIL_KEY) { maskSensitive(ctx.second) }
                    }
                }
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
            log.atWarn()
                .addKeyValue(INVITE_ID_KEY) { id }
                .addKeyValue(INVITE_EXPIRY_MINUTES_KEY) { expiryMinutes }
                .log { "Refusing to resend invite due to invalid expiryMinutes" }
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
        var inviteContext: Pair<String, String>? = null
        return runCatching {
            val invite = inviteService.get(id).also {
                inviteContext = it.realm to it.email
            }

            withInviteContextInMdc(invite.id, invite.realm, invite.email) {
                val allowedRoles = fetchAllowedRolesForResend(id, invite.realm, invite.roles, redirectAttributes)
                    ?: return@withInviteContextInMdc "redirect:/admin/invite"

                if (invite.roles.isNotEmpty() && !allowedRoles.containsAll(invite.roles)) {
                    return@withInviteContextInMdc refuseResendDueToMissingRoles(id, invite.realm, redirectAttributes)
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
            }
        }.getOrElse {
            log.dedupedEventForInviteError(it)
                .addKeyValue(INVITE_ID_KEY) { id }
                .apply {
                    inviteContext?.let { ctx ->
                        addKeyValue(INVITE_REALM_KEY) { ctx.first }
                        addKeyValue(INVITE_EMAIL_KEY) { maskSensitive(ctx.second) }
                    }
                }
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
                log.atDebug()
                    .addKeyValue(INVITE_ID_KEY) { inviteId }
                    .addKeyValue(INVITE_REALM_KEY) { realm }
                    .setCause(it)
                    .log { "Failed to fetch realm roles before resend (Keycloak client logged details)" }
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
            .addKeyValue(INVITE_ID_KEY) { inviteId }
            .addKeyValue(INVITE_REALM_KEY) { realm }
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
                inviteId = created.invite.id,
                realm = created.invite.realm,
                email = created.invite.email,
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
            log.atDebug()
                .addKeyValue(INVITE_REALM_KEY) { realm }
                .setCause(it)
                .log { "Failed to fetch roles for realm (Keycloak client logged details)" }
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
                log.atDebug()
                    .addKeyValue(INVITE_REALM_KEY) { realm }
                    .setCause(it)
                    .log { "Failed to fetch roles for validation (Keycloak client logged details)" }
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
