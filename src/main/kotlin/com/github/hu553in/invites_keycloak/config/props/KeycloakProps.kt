package com.github.hu553in.invites_keycloak.config.props

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import org.hibernate.validator.constraints.URL
import org.hibernate.validator.constraints.time.DurationMin
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@ConfigurationProperties(prefix = "keycloak")
@Validated
data class KeycloakProps(
    @field:NotBlank
    @field:URL
    val url: String,
    @field:NotBlank
    val realm: String,
    @field:NotBlank
    val clientId: String,
    @field:NotBlank
    val clientSecret: String,
    @field:NotBlank
    val requiredRole: String,
    @field:NotNull
    @field:Positive
    val maxAttempts: Long,
    @field:NotNull
    @field:DurationMin(millis = 1)
    val minBackoff: Duration,
    @field:NotNull
    @field:DurationMin(millis = 1)
    val connectTimeout: Duration,
    @field:NotNull
    @field:DurationMin(seconds = 1)
    val responseTimeout: Duration,
)
