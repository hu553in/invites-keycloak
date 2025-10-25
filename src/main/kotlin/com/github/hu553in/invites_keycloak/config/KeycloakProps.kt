package com.github.hu553in.invites_keycloak.config

import jakarta.validation.constraints.NotBlank
import org.hibernate.validator.constraints.URL
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@ConfigurationProperties(prefix = "keycloak")
@Validated
data class KeycloakProps(
    @field:NotBlank
    @field:URL
    val url: String,
    @field:NotBlank
    val clientId: String,
    @field:NotBlank
    val clientSecret: String
)
