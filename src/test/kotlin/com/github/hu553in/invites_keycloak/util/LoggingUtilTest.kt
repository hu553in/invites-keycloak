package com.github.hu553in.invites_keycloak.util

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.hu553in.invites_keycloak.exception.InvalidInviteException
import com.github.hu553in.invites_keycloak.exception.InvalidInviteReason
import com.github.hu553in.invites_keycloak.exception.KeycloakAdminClientException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import java.util.*

class LoggingUtilTest {

    private lateinit var logger: Logger
    private lateinit var listAppender: ListAppender<ILoggingEvent>
    private var previousLevel: Level? = null

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger("test.logging-util") as Logger
        previousLevel = logger.level
        logger.level = Level.DEBUG
        MDC.clear()
        listAppender = ListAppender()
        listAppender.start()
        logger.addAppender(listAppender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(listAppender)
        logger.level = previousLevel
        MDC.clear()
    }

    @Test
    fun `eventForAppError logs illegal argument at warn level`() {
        // act
        logger.eventForAppError(IllegalArgumentException("bad input"))
            .log("test")

        // assert
        assertThat(lastLevel()).isEqualTo(Level.WARN)
    }

    @Test
    fun `eventForAppError logs invalid invite at warn level`() {
        // act
        logger.eventForAppError(InvalidInviteException(reason = InvalidInviteReason.EXPIRED))
            .log("test")

        // assert
        assertThat(lastLevel()).isEqualTo(Level.WARN)
    }

    @Test
    fun `eventForAppError treats keycloak misconfiguration statuses as error`() {
        // act
        logger.eventForAppError(KeycloakAdminClientException("bad config", HttpStatus.NOT_FOUND))
            .log("test")

        // assert
        assertThat(lastLevel()).isEqualTo(Level.ERROR)
    }

    @Test
    fun `eventForAppError treats non-misconfiguration keycloak 4xx as warn`() {
        // act
        logger.eventForAppError(KeycloakAdminClientException("rate limited", HttpStatus.TOO_MANY_REQUESTS))
            .log("test")

        // assert
        assertThat(lastLevel()).isEqualTo(Level.WARN)
    }

    @Test
    fun `dedupedEventForAppError downgrades nested keycloak errors to debug`() {
        // arrange
        val nested = RuntimeException(
            "outer",
            IllegalStateException("inner", KeycloakAdminClientException("kc", HttpStatus.BAD_GATEWAY)),
        )

        // act
        logger.dedupedEventForAppError(nested)
            .log("test")

        // assert
        assertThat(lastLevel()).isEqualTo(Level.DEBUG)
    }

    @Test
    fun `withMdc trims values supports nested overrides and restores previous state`() {
        // arrange
        MDC.put("alpha", "original")
        MDC.put("beta", "keep")

        // act
        withMdc(
            "alpha" to "  outer  ",
            "beta" to "   ",
            "gamma" to "  value  ",
        ) {
            // assert
            assertThat(MDC.get("alpha")).isEqualTo("outer")
            assertThat(MDC.get("beta")).isNull()
            assertThat(MDC.get("gamma")).isEqualTo("value")

            withMdc("alpha" to " nested ", "gamma" to null) {
                assertThat(MDC.get("alpha")).isEqualTo("nested")
                assertThat(MDC.get("gamma")).isNull()
            }

            assertThat(MDC.get("alpha")).isEqualTo("outer")
            assertThat(MDC.get("gamma")).isEqualTo("value")
        }

        assertThat(MDC.get("alpha")).isEqualTo("original")
        assertThat(MDC.get("beta")).isEqualTo("keep")
        assertThat(MDC.get("gamma")).isNull()
    }

    @Test
    fun `withInviteContextInMdc stores keycloak realm and masked email then restores MDC`() {
        // arrange
        val inviteId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        MDC.put(KEYCLOAK_REALM_KEY, "existing-realm")

        // act
        withInviteContextInMdc(inviteId, " master ", " user@example.com ") {
            // assert
            assertThat(MDC.get(INVITE_ID_KEY)).isEqualTo(inviteId.toString())
            assertThat(MDC.get(KEYCLOAK_REALM_KEY)).isEqualTo("master")
            assertThat(MDC.get(INVITE_EMAIL_KEY)).isEqualTo("u***m")
        }

        assertThat(MDC.get(INVITE_ID_KEY)).isNull()
        assertThat(MDC.get(INVITE_EMAIL_KEY)).isNull()
        assertThat(MDC.get(KEYCLOAK_REALM_KEY)).isEqualTo("existing-realm")
    }

    private fun lastLevel(): Level = listAppender.list.last().level
}
