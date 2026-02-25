package com.github.hu553in.invites_keycloak.config.props

import com.github.hu553in.invites_keycloak.util.MailMessages
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@ConfigurationProperties(prefix = "mail")
@Validated
data class MailProps(
    val from: String? = null,
    @field:NotBlank
    val subjectTemplate: String = MailMessages.DEFAULT_INVITE_SUBJECT_TEMPLATE
)
