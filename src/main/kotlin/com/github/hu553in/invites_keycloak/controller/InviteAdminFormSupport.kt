package com.github.hu553in.invites_keycloak.controller

import com.github.hu553in.invites_keycloak.config.props.InviteProps
import org.springframework.security.core.Authentication
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import java.time.Duration

object InviteAdminFormSupport {
    data class RoleFetchResult(
        val roles: List<String>,
        val errorMessage: String?,
        val available: Boolean
    )

    fun addExpiryMetadata(model: Model, inviteProps: InviteProps) {
        model.addAttribute("expiryDefaultMinutes", inviteProps.expiry.default.toMinutes())
        model.addAttribute("expiryMinMinutes", inviteProps.expiry.min.toMinutes())
        model.addAttribute("expiryMaxMinutes", inviteProps.expiry.max.toMinutes())
    }

    fun ensureFormPresent(model: Model, realm: String, inviteProps: InviteProps, rolesAvailable: Boolean = true) {
        if (!model.containsAttribute("inviteForm")) {
            model.addAttribute("inviteForm", createDefaultForm(realm, inviteProps, rolesAvailable))
            return
        }

        val attribute = model.getAttribute("inviteForm")
        if (attribute is InviteAdminController.InviteForm) {
            attribute.realm = realm
            if (rolesAvailable && attribute.roles.isEmpty()) {
                attribute.roles.addAll(defaultRoles(realm, inviteProps))
            }
            if (!rolesAvailable) {
                attribute.roles.clear()
            }
        } else {
            model.addAttribute("inviteForm", createDefaultForm(realm, inviteProps, rolesAvailable))
        }
    }

    fun populateFormMetadata(
        model: Model,
        realm: String,
        inviteProps: InviteProps,
        roleFetch: RoleFetchResult
    ) {
        val availableRealms = inviteProps.realms.keys.toList()
        val defaultsForView = if (roleFetch.available) defaultRoles(realm, inviteProps) else emptySet()

        model.addAttribute("realmOptions", availableRealms)
        model.addAttribute("selectedRealm", realm)
        model.addAttribute("roleOptions", roleFetch.roles)
        model.addAttribute("rolesFetchError", roleFetch.errorMessage)
        model.addAttribute("defaultRoles", defaultsForView)
        model.addAttribute("rolesAvailable", roleFetch.available)
        addExpiryMetadata(model, inviteProps)
    }

    fun validateRealm(realm: String, inviteProps: InviteProps, bindingResult: BindingResult): String? {
        val normalized = realm.trim()
        return when {
            normalized.isEmpty() -> {
                bindingResult.rejectValue("realm", "realm.empty", "Realm is required")
                null
            }

            !inviteProps.realms.containsKey(normalized) -> {
                bindingResult.rejectValue("realm", "realm.invalid", "Realm is not allowed")
                null
            }

            else -> normalized
        }
    }

    fun validateExpiryMinutes(
        expiryMinutes: Long?,
        inviteProps: InviteProps,
        bindingResult: BindingResult? = null
    ): Duration? {
        val minMinutes = inviteProps.expiry.min.toMinutes()
        val maxMinutes = inviteProps.expiry.max.toMinutes()

        val duration = expiryMinutes
            .takeIf { it in minMinutes..maxMinutes }
            ?.let { Duration.ofMinutes(it) }

        if (duration == null && bindingResult != null) {
            bindingResult.rejectValue(
                "expiryMinutes",
                "expiry.invalid",
                "Expiry must be between $minMinutes and $maxMinutes minutes"
            )
        }
        return duration
    }

    fun resolveRealmOrDefault(realm: String?, inviteProps: InviteProps): String {
        val available = inviteProps.realms.keys.toList()
        require(available.isNotEmpty()) { "At least one realm must be configured" }
        val trimmed = realm?.trim()
        return if (trimmed != null && inviteProps.realms.containsKey(trimmed)) {
            trimmed
        } else {
            available.first()
        }
    }

    fun createDefaultForm(
        realm: String,
        inviteProps: InviteProps,
        rolesAvailable: Boolean = true
    ): InviteAdminController.InviteForm {
        val defaults = if (rolesAvailable) defaultRoles(realm, inviteProps) else emptySet()
        return InviteAdminController.InviteForm(
            realm = realm,
            email = "",
            expiryMinutes = inviteProps.expiry.default.toMinutes(),
            maxUses = 1,
            roles = LinkedHashSet(defaults)
        )
    }

    fun defaultRoles(realm: String, inviteProps: InviteProps): Set<String> {
        val configured = inviteProps.realms[realm]?.defaultRoles.orEmpty()
        return configured
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toCollection(LinkedHashSet())
    }

    fun Authentication?.nameOrSystem(): String {
        val trimmed = this?.name?.trim().orEmpty()
        return trimmed.ifEmpty { "system" }
    }
}
