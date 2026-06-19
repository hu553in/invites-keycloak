package com.github.hu553in.invites_keycloak.config.props

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@ConfigurationProperties(prefix = "mail")
@Validated
data class MailProps(val from: String? = null, val subjectTemplate: String? = null)
