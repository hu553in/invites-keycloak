package com.github.hu553in.invites_keycloak.service

import com.github.hu553in.invites_keycloak.config.props.MailProps
import com.github.hu553in.invites_keycloak.service.MailService.InviteMailData
import com.github.hu553in.invites_keycloak.service.MailService.MailSendStatus
import com.github.hu553in.invites_keycloak.util.objectProvider
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito
import org.mockito.BDDMockito.then
import org.mockito.BDDMockito.willAnswer
import org.mockito.BDDMockito.willThrow
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessagePreparator
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.time.Clock
import java.time.Instant
import java.util.*

class MailServiceTest {

    private val clock = Clock.systemUTC()

    private val templateEngine = SpringTemplateEngine().apply {
        setTemplateResolver(
            ClassLoaderTemplateResolver().apply {
                prefix = "templates/"
                suffix = ".html"
                characterEncoding = Charsets.UTF_8.name()
                templateMode = TemplateMode.HTML
            }
        )
    }

    @Test
    fun `sendInviteEmail sends message when mail sender configured`() {
        // arrange
        val sender = BDDMockito.mock(JavaMailSender::class.java)
        val msg = MimeMessage(Session.getInstance(Properties()))

        willAnswer { invocation ->
            val preparator = invocation.getArgument<MimeMessagePreparator>(0)
            preparator.prepare(msg)
            null
        }.given(sender).send(any(MimeMessagePreparator::class.java))

        val svc = MailService(objectProvider(sender), templateEngine, MailProps())
        val data = InviteMailData(
            inviteId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            realm = "master",
            email = "user@example.com",
            link = "https://example.org/invite/master/token",
            expiresAt = Instant.parse("2025-02-01T10:15:30Z")
        )

        // act
        val status = svc.sendInviteEmail(data)

        // assert
        assertThat(status).isEqualTo(MailSendStatus.OK)
        assertThat(msg.subject).isEqualTo("Invitation to master")
        assertThat(msg.allRecipients).hasSize(1)
        assertThat(msg.allRecipients[0].toString()).isEqualTo("user@example.com")
        assertThat(msg.content.toString()).contains(data.link)

        then(sender).should().send(any(MimeMessagePreparator::class.java))
    }

    @Test
    fun `sendInviteEmail skips when sender missing`() {
        // arrange
        val svc = MailService(objectProvider(null), templateEngine, MailProps())

        val data = InviteMailData(
            inviteId = null,
            realm = "master",
            email = "user@example.com",
            link = "https://example.org/invite/master/token",
            expiresAt = clock.instant()
        )

        // act
        val status = svc.sendInviteEmail(data)

        // assert
        assertThat(status).isEqualTo(MailSendStatus.NOT_CONFIGURED)
    }

    @Test
    fun `sendInviteEmail returns failed when sender throws`() {
        // arrange
        val sender = BDDMockito.mock(JavaMailSender::class.java)
        willThrow(MailSendException("boom")).given(sender).send(any(MimeMessagePreparator::class.java))

        val svc = MailService(objectProvider(sender), templateEngine, MailProps())
        val data = InviteMailData(
            inviteId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            realm = "master",
            email = "user@example.com",
            link = "https://example.org/invite/master/token",
            expiresAt = clock.instant()
        )

        // act
        val status = svc.sendInviteEmail(data)

        // assert
        assertThat(status).isEqualTo(MailSendStatus.FAIL)
    }
}
