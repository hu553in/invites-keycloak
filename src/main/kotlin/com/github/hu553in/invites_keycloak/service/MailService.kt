package com.github.hu553in.invites_keycloak.service

import com.github.hu553in.invites_keycloak.config.props.MailProps
import com.github.hu553in.invites_keycloak.util.MAIL_STATUS_KEY
import com.github.hu553in.invites_keycloak.util.MailMessages
import com.github.hu553in.invites_keycloak.util.logger
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
            if (sender == null) {
                MailSendStatus.NOT_CONFIGURED.also {
                    log.atWarn()
                        .addKeyValue(MAIL_STATUS_KEY) { it.logValue() }
                        .log { "Mail sender is not configured" }
                }
            } else {
                log.atDebug()
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
                        .addKeyValue(MAIL_STATUS_KEY) { MailSendStatus.OK.logValue() }
                        .log { "Invite email sent" }
                    MailSendStatus.OK
                } catch (e: MailException) {
                    MailSendStatus.FAIL.also {
                        log.atError()
                            .addKeyValue(MAIL_STATUS_KEY) { it.logValue() }
                            .setCause(e)
                            .log { "Failed to send invite email" }
                    }
                } catch (e: MessagingException) {
                    MailSendStatus.FAIL.also {
                        log.atError()
                            .addKeyValue(MAIL_STATUS_KEY) { it.logValue() }
                            .setCause(e)
                            .log { "Failed to build invite email" }
                    }
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
                MailMessages.defaultInviteSubject(realm)
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
        FAIL;

        fun logValue(): String = name.lowercase()
    }
}
