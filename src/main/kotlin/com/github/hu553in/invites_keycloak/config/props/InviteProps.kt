package com.github.hu553in.invites_keycloak.config.props

import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import org.hibernate.validator.constraints.URL
import org.hibernate.validator.constraints.time.DurationMin
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@ConfigurationProperties(prefix = "invite")
@Validated
data class InviteProps(
    @field:NotBlank
    @field:URL
    val publicBaseUrl: String,
    @field:Valid
    @field:NotNull
    val expiry: ExpiryProps,
    @field:Valid
    @field:NotEmpty
    val realms: Map<String, RealmProps>,
    @field:Valid
    @field:NotNull
    val token: TokenProps,
    @field:Valid
    @field:NotNull
    val cleanup: CleanupProps
) {
    @get:AssertTrue(message = "invite.expiry must satisfy min <= default <= max")
    val isExpiryRangeValid: Boolean
        get() = expiry.default >= expiry.min && expiry.default <= expiry.max

    @Validated
    data class RealmProps(
        @field:NotEmpty
        val defaultRoles: Set<@NotBlank String>
    )

    @Validated
    data class ExpiryProps(
        @field:NotNull
        @field:DurationMin(minutes = 5)
        val default: Duration,
        @field:NotNull
        @field:DurationMin(minutes = 5)
        val min: Duration,
        @field:NotNull
        @field:DurationMin(minutes = 5)
        val max: Duration
    )

    @Validated
    data class TokenProps(
        @field:NotBlank
        val secret: String,
        @field:Min(16)
        @field:Max(64)
        @field:NotNull
        val bytes: Int,
        @field:Min(8)
        @field:Max(64)
        @field:NotNull
        val saltBytes: Int,
        @field:NotBlank
        val macAlgorithm: String
    )

    @Validated
    data class CleanupProps(
        @field:NotNull
        @field:DurationMin(days = 1)
        val retention: Duration
    )
}
