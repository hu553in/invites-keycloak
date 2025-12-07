package com.github.hu553in.invites_keycloak.controller

import com.github.hu553in.invites_keycloak.config.props.InviteProps
import com.github.hu553in.invites_keycloak.util.userIdOrSystem
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

    fun ensureFormPresent(
        model: Model,
        realm: String,
        inviteProps: InviteProps,
        rolesAvailable: Boolean = true,
        allowedRoles: Set<String>? = null
    ) {
        val configuredRoles = filteredConfiguredRoles(realm, inviteProps, allowedRoles)

        if (!model.containsAttribute("inviteForm")) {
            model.addAttribute("inviteForm", createDefaultForm(realm, inviteProps, rolesAvailable, configuredRoles))
            return
        }

        val attribute = model.getAttribute("inviteForm")
        if (attribute is InviteAdminController.InviteForm) {
            attribute.realm = realm
            if (rolesAvailable) {
                if (allowedRoles != null) {
                    attribute.roles.retainAll(allowedRoles)
                }
                if (attribute.roles.isEmpty()) {
                    attribute.roles.addAll(configuredRoles)
                }
            }
            if (!rolesAvailable) {
                attribute.roles.clear()
            }
        } else {
            model.addAttribute("inviteForm", createDefaultForm(realm, inviteProps, rolesAvailable, configuredRoles))
        }
    }

    fun populateFormMetadata(
        model: Model,
        realm: String,
        inviteProps: InviteProps,
        roleFetch: RoleFetchResult,
        roleOptions: List<String>,
        rolesVisible: Boolean,
        rolesAvailable: Boolean
    ) {
        val availableRealms = inviteProps.realms.keys.toList()

        model.addAttribute("realmOptions", availableRealms)
        model.addAttribute("selectedRealm", realm)
        model.addAttribute("roleOptions", roleOptions)
        model.addAttribute(
            "rolesFetchError",
            if (rolesVisible) {
                roleFetch.errorMessage
            } else {
                null
            }
        )
        model.addAttribute("rolesAvailable", rolesAvailable)
        model.addAttribute("rolesVisible", rolesVisible)
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
        rolesAvailable: Boolean = true,
        configuredOverride: Set<String>? = null
    ): InviteAdminController.InviteForm {
        val configured = if (rolesAvailable) {
            configuredOverride ?: configuredRoles(realm, inviteProps)
        } else {
            emptySet()
        }
        return InviteAdminController.InviteForm(
            realm = realm,
            email = "",
            expiryMinutes = inviteProps.expiry.default.toMinutes(),
            maxUses = 1,
            roles = LinkedHashSet(configured)
        )
    }

    fun configuredRoles(realm: String, inviteProps: InviteProps): Set<String> {
        return inviteProps.realms[realm]
            ?.roles
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toCollection(LinkedHashSet())
    }

    fun rolesForView(realm: String, inviteProps: InviteProps, roleFetch: RoleFetchResult): List<String> {
        if (!roleFetch.available) {
            return emptyList()
        }

        val configuredRoles = configuredRoles(realm, inviteProps)
        val keycloakRoles = roleFetch.roles.toSet()
        return configuredRoles.filter { keycloakRoles.contains(it) }
    }

    private fun filteredConfiguredRoles(
        realm: String,
        inviteProps: InviteProps,
        allowedRoles: Set<String>?
    ): Set<String> {
        val configuredRoles = configuredRoles(realm, inviteProps)
        return if (allowedRoles != null) {
            configuredRoles.filterTo(LinkedHashSet()) { allowedRoles.contains(it) }
        } else {
            configuredRoles
        }
    }

    fun Authentication?.nameOrSystem(): String {
        return this.userIdOrSystem()
    }
}
