package com.github.hu553in.invites_keycloak.service

import com.github.hu553in.invites_keycloak.config.props.MailProps
import com.github.hu553in.invites_keycloak.util.INVITE_EMAIL_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_ID_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_REALM_KEY
import com.github.hu553in.invites_keycloak.util.logger
import com.github.hu553in.invites_keycloak.util.maskSensitive
import com.github.hu553in.invites_keycloak.util.withInviteContextInMdc
import jakarta.mail.MessagingException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import java.time.Instant
import java.util.*

@Service
class MailService(
    private val senderProvider: ObjectProvider<JavaMailSender>,
    private val templateEngine: SpringTemplateEngine,
    private val mailProps: MailProps
) {

    private val log by logger()

    fun sendInviteEmail(data: InviteMailData): MailSendStatus {
        return withInviteContextInMdc(data.inviteId, data.realm, data.email) {
            val sender = senderProvider.ifAvailable
                ?: return@withInviteContextInMdc MailSendStatus.NOT_CONFIGURED.also {
                    log.atWarn()
                        .addKeyValue(INVITE_ID_KEY) { data.inviteId }
                        .addKeyValue(INVITE_REALM_KEY) { data.realm }
                        .addKeyValue(INVITE_EMAIL_KEY) { maskSensitive(data.email) }
                        .log { "Mail sender is not configured" }
                }

            log.atDebug()
                .addKeyValue(INVITE_ID_KEY) { data.inviteId }
                .addKeyValue(INVITE_REALM_KEY) { data.realm }
                .addKeyValue(INVITE_EMAIL_KEY) { maskSensitive(data.email) }
                .log { "Sending invite email" }

            try {
                sender.send {
                    MimeMessageHelper(it, Charsets.UTF_8.name()).also { helper ->
                        mailProps.from
                            ?.takeIf { from -> from.isNotBlank() }
                            ?.let { from -> helper.setFrom(from.trim()) }
                        helper.setTo(data.email)
                        helper.setSubject(resolveSubject(data.realm))
                        helper.setText(renderBody(data), true)
                    }
                }
                log.atInfo()
                    .addKeyValue(INVITE_ID_KEY) { data.inviteId }
                    .addKeyValue(INVITE_REALM_KEY) { data.realm }
                    .addKeyValue(INVITE_EMAIL_KEY) { maskSensitive(data.email) }
                    .log { "Invite email sent" }
                MailSendStatus.OK
            } catch (e: MailException) {
                MailSendStatus.FAIL.also {
                    log.atError()
                        .addKeyValue(INVITE_ID_KEY) { data.inviteId }
                        .addKeyValue(INVITE_REALM_KEY) { data.realm }
                        .addKeyValue(INVITE_EMAIL_KEY) { maskSensitive(data.email) }
                        .setCause(e)
                        .log { "Failed to send invite email" }
                }
            } catch (e: MessagingException) {
                MailSendStatus.FAIL.also {
                    log.atError()
                        .addKeyValue(INVITE_ID_KEY) { data.inviteId }
                        .addKeyValue(INVITE_REALM_KEY) { data.realm }
                        .addKeyValue(INVITE_EMAIL_KEY) { maskSensitive(data.email) }
                        .setCause(e)
                        .log { "Failed to build invite email" }
                }
            }
        }
    }

    private fun resolveSubject(realm: String): String {
        return runCatching { mailProps.subjectTemplate.format(realm) }
            .getOrElse {
                log.atWarn()
                    .setCause(it)
                    .log { "Falling back to default invite email subject template" }
                "Invitation to $realm"
            }
    }

    private fun renderBody(data: InviteMailData): String {
        return templateEngine.process(
            "mail/invite",
            Context().apply {
                setVariable("link", data.link)
                setVariable("target", data.realm)
                setVariable("expiresAt", data.expiresAt)
            }
        )
    }

    data class InviteMailData(
        val inviteId: UUID?,
        val realm: String,
        val email: String,
        val link: String,
        val expiresAt: Instant
    )

    enum class MailSendStatus {
        NOT_CONFIGURED,
        OK,
        FAIL
    }
}
