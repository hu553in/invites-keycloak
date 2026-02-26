package com.github.hu553in.invites_keycloak.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.hu553in.invites_keycloak.config.props.MailProps
import com.github.hu553in.invites_keycloak.service.MailService.InviteMailData
import com.github.hu553in.invites_keycloak.service.MailService.MailSendStatus
import com.github.hu553in.invites_keycloak.util.INVITE_EMAIL_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_ID_KEY
import com.github.hu553in.invites_keycloak.util.KEYCLOAK_REALM_KEY
import com.github.hu553in.invites_keycloak.util.MAIL_STATUS_KEY
import com.github.hu553in.invites_keycloak.util.MailMessages
import com.github.hu553in.invites_keycloak.util.maskSensitive
import com.github.hu553in.invites_keycloak.util.objectProvider
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito
import org.mockito.BDDMockito.then
import org.mockito.BDDMockito.willAnswer
import org.mockito.BDDMockito.willThrow
import org.slf4j.LoggerFactory
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
            },
        )
    }

    private lateinit var logger: Logger
    private lateinit var listAppender: ListAppender<ILoggingEvent>

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger(MailService::class.java) as Logger
        listAppender = ListAppender()
        listAppender.start()
        logger.addAppender(listAppender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(listAppender)
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
            expiresAt = Instant.parse("2025-02-01T10:15:30Z"),
        )

        // act
        val status = svc.sendInviteEmail(data)

        // assert
        assertThat(status).isEqualTo(MailSendStatus.OK)
        assertThat(msg.subject).isEqualTo(MailMessages.defaultInviteSubject("master"))
        assertThat(msg.allRecipients).hasSize(1)
        assertThat(msg.allRecipients[0].toString()).isEqualTo("user@example.com")
        assertThat(msg.content.toString()).contains(data.link)

        then(sender).should().send(any(MimeMessagePreparator::class.java))

        val sentEvent = listAppender.list.first { it.formattedMessage == "Invite email sent" }
        assertThat(sentEvent.level).isEqualTo(Level.INFO)
        assertThat(sentEvent.keyValues()).containsEntry(MAIL_STATUS_KEY, "ok")
        assertThat(sentEvent.mdcPropertyMap).containsEntry(INVITE_ID_KEY, data.inviteId.toString())
        assertThat(sentEvent.mdcPropertyMap).containsEntry(KEYCLOAK_REALM_KEY, data.realm)
        assertThat(sentEvent.mdcPropertyMap).containsEntry(INVITE_EMAIL_KEY, maskSensitive(data.email))
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
            expiresAt = clock.instant(),
        )

        // act
        val status = svc.sendInviteEmail(data)

        // assert
        assertThat(status).isEqualTo(MailSendStatus.NOT_CONFIGURED)

        val event = listAppender.list.first { it.formattedMessage == "Mail sender is not configured" }
        assertThat(event.level).isEqualTo(Level.WARN)
        assertThat(event.keyValues()).containsEntry(MAIL_STATUS_KEY, "not_configured")
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
            expiresAt = clock.instant(),
        )

        // act
        val status = svc.sendInviteEmail(data)

        // assert
        assertThat(status).isEqualTo(MailSendStatus.FAIL)

        val event = listAppender.list.first { it.formattedMessage == "Failed to send invite email" }
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(event.keyValues()).containsEntry(MAIL_STATUS_KEY, "fail")
    }

    @Test
    fun `sendInviteEmail returns failed when building message throws messaging exception`() {
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
            email = "invalid-email@",
            link = "https://example.org/invite/master/token",
            expiresAt = clock.instant(),
        )

        // act
        val status = svc.sendInviteEmail(data)

        // assert
        assertThat(status).isEqualTo(MailSendStatus.FAIL)
        val event = listAppender.list.first { it.formattedMessage == "Failed to build invite email" }
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(event.keyValues()).containsEntry(MAIL_STATUS_KEY, "fail")
    }

    @Test
    fun `sendInviteEmail falls back to default subject when subject template is invalid`() {
        // arrange
        val sender = BDDMockito.mock(JavaMailSender::class.java)
        val msg = MimeMessage(Session.getInstance(Properties()))
        willAnswer { invocation ->
            val preparator = invocation.getArgument<MimeMessagePreparator>(0)
            preparator.prepare(msg)
            null
        }.given(sender).send(any(MimeMessagePreparator::class.java))

        val svc = MailService(
            objectProvider(sender),
            templateEngine,
            MailProps(subjectTemplate = "Invitation to %q"),
        )
        val data = InviteMailData(
            inviteId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            realm = "master",
            email = "user@example.com",
            link = "https://example.org/invite/master/token",
            expiresAt = clock.instant(),
        )

        // act
        val status = svc.sendInviteEmail(data)

        // assert
        assertThat(status).isEqualTo(MailSendStatus.OK)
        assertThat(msg.subject).isEqualTo(MailMessages.defaultInviteSubject("master"))
        val fallbackEvent = listAppender.list.first {
            it.formattedMessage == "Falling back to default invite email subject template"
        }
        assertThat(fallbackEvent.level).isEqualTo(Level.WARN)
        val sentEvent = listAppender.list.first { it.formattedMessage == "Invite email sent" }
        assertThat(sentEvent.keyValues()).containsEntry(MAIL_STATUS_KEY, "ok")
    }

    private fun ILoggingEvent.keyValues(): Map<String, String> = (this.keyValuePairs ?: emptyList())
        .associate { it.key to (it.value?.toString() ?: "null") }
}
