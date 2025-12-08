package com.github.hu553in.invites_keycloak.service

import com.github.hu553in.invites_keycloak.config.props.MailProps
import com.github.hu553in.invites_keycloak.util.logger
import jakarta.mail.MessagingException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import java.time.Instant

@Service
class MailService(
    private val senderProvider: ObjectProvider<JavaMailSender>,
    private val templateEngine: SpringTemplateEngine,
    private val mailProps: MailProps
) {

    private val log by logger()

    fun sendInviteEmail(data: InviteMailData): MailSendStatus {
        val sender = senderProvider.ifAvailable
            ?: return MailSendStatus.NOT_CONFIGURED.also {
                log.atWarn()
                    .log { "Mail sender is not configured" }
            }

        return try {
            sender.send {
                MimeMessageHelper(it, Charsets.UTF_8.name()).also { helper ->
                    mailProps.from
                        ?.takeIf { from -> from.isNotBlank() }
                        ?.let { from -> helper.setFrom(from.trim()) }
                    helper.setTo(data.email)
                    helper.setSubject(resolveSubject(data.target))
                    helper.setText(renderBody(data), true)
                }
            }
            MailSendStatus.OK
        } catch (e: MailException) {
            MailSendStatus.FAIL.also {
                log.atError()
                    .setCause(e)
                    .log { "Failed to send invite email" }
            }
        } catch (e: MessagingException) {
            MailSendStatus.FAIL.also {
                log.atError()
                    .setCause(e)
                    .log { "Failed to build invite email" }
            }
        }
    }

    private fun resolveSubject(target: String): String {
        return runCatching { mailProps.subjectTemplate.format(target) }
            .getOrElse {
                log.atWarn()
                    .setCause(it)
                    .log { "Falling back to default invite email subject template" }
                "Invitation to $target"
            }
    }

    private fun renderBody(data: InviteMailData): String {
        return templateEngine.process(
            "mail/invite",
            Context().apply {
                setVariable("link", data.link)
                setVariable("target", data.target)
                setVariable("expiresAt", data.expiresAt)
            }
        )
    }

    data class InviteMailData(
        val email: String,
        val target: String,
        val link: String,
        val expiresAt: Instant
    )

    enum class MailSendStatus {
        NOT_CONFIGURED,
        OK,
        FAIL
    }
}
