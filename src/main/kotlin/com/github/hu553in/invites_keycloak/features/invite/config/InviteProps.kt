package com.github.hu553in.invites_keycloak.features.invite.config

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@ConfigurationProperties(prefix = "invite")
@Validated
data class InviteProps(
    @field:NotNull
    val defaultExpiry: Duration,
    @field:NotNull
    val minExpiry: Duration,
    @field:NotNull
    val maxExpiry: Duration,
    @field:Valid
    @field:NotEmpty
    val realms: Map<String, RealmConfig>
) {
    @Validated
    data class RealmConfig(
        @field:NotEmpty
        val defaultRoles: Set<@NotBlank String>
    )
}
