package com.github.hu553in.invites_keycloak.features.keycloak.config

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import org.hibernate.validator.constraints.URL
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
    val minBackoff: Duration
)
