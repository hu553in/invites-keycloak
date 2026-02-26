package com.github.hu553in.invites_keycloak.exception.handler

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.hu553in.invites_keycloak.config.TestClientRegistrationRepositoryConfig
import com.github.hu553in.invites_keycloak.exception.InvalidInviteException
import com.github.hu553in.invites_keycloak.exception.InvalidInviteReason
import com.github.hu553in.invites_keycloak.exception.InviteNotFoundException
import com.github.hu553in.invites_keycloak.exception.KeycloakAdminClientException
import com.github.hu553in.invites_keycloak.util.ErrorMessages
import com.github.hu553in.invites_keycloak.util.INVITE_INVALID_REASON_KEY
import com.github.hu553in.invites_keycloak.util.REQUEST_METHOD_KEY
import com.github.hu553in.invites_keycloak.util.REQUEST_ROUTE_KEY
import com.github.hu553in.invites_keycloak.util.REQUEST_STATUS_KEY
import com.github.hu553in.invites_keycloak.util.REQUEST_URI_KEY
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.stereotype.Controller
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.GetMapping

@WebMvcTest(controllers = [TestThrowingController::class])
@AutoConfigureMockMvc
@Import(
    ControllerExceptionHandler::class,
    TestClientRegistrationRepositoryConfig::class,
)
@ActiveProfiles("test")
class ControllerExceptionHandlerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    private lateinit var listAppender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger
    private var previousLevel: Level? = null

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger(ControllerExceptionHandler::class.java) as Logger
        previousLevel = logger.level
        logger.level = Level.DEBUG
        listAppender = ListAppender()
        listAppender.start()
        logger.addAppender(listAppender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(listAppender)
        logger.level = previousLevel
    }

    @Test
    @WithMockUser
    fun `maps Keycloak client 4xx to bad request with invite message`() {
        // act
        val result = mockMvc.get("/handler/keycloak-4xx")

        // assert
        result.andExpect {
            status { isBadRequest() }
            view { name("generic_error") }
            model {
                attribute("error_message", ErrorMessages.INVITE_CANNOT_BE_REDEEMED)
                attribute("error_details", ErrorMessages.INVITE_CANNOT_BE_REDEEMED_DETAILS)
            }
        }
    }

    @Test
    @WithMockUser
    fun `maps Keycloak server 5xx to service unavailable with generic service message`() {
        // act
        val result = mockMvc.get("/handler/keycloak-5xx")

        // assert
        result.andExpect {
            status { isServiceUnavailable() }
            view { name("generic_error") }
            model {
                attribute("error_message", ErrorMessages.SERVICE_TEMP_UNAVAILABLE)
                attribute("error_details", ErrorMessages.SERVICE_TEMP_UNAVAILABLE_DETAILS)
            }
        }
    }

    @Test
    @WithMockUser
    fun `maps invalid invite exception to unauthorized generic error`() {
        // act
        val result = mockMvc.get("/handler/invalid-invite")

        // assert
        result.andExpect {
            status { isUnauthorized() }
            view { name("generic_error") }
            model {
                attribute("error_message", ErrorMessages.INVITE_INVALID)
                attribute("error_details", ErrorMessages.INVITE_INVALID_DETAILS)
            }
        }

        val event =
            listAppender.list.first { it.formattedMessage.contains("InvalidInviteException exception occurred") }
        assertThat(event.level).isEqualTo(Level.WARN)
        assertThat(event.keyValues()).containsEntry(REQUEST_METHOD_KEY, "GET")
        assertThat(event.keyValues()).containsEntry(REQUEST_ROUTE_KEY, "/handler/invalid-invite")
        assertThat(event.keyValues()).containsEntry(REQUEST_URI_KEY, "/handler/invalid-invite")
        assertThat(event.keyValues()).containsEntry(REQUEST_STATUS_KEY, "401")
        assertThat(event.keyValues()).containsEntry(INVITE_INVALID_REASON_KEY, "expired")
    }

    @Test
    @WithMockUser
    fun `logs malformed invalid invite reason with request context`() {
        // act
        val result = mockMvc.get("/handler/invalid-invite-malformed")

        // assert
        result.andExpect {
            status { isUnauthorized() }
            view { name("generic_error") }
        }

        val event =
            listAppender.list.first { it.formattedMessage.contains("InvalidInviteException exception occurred") }
        assertThat(event.level).isEqualTo(Level.WARN)
        assertThat(event.keyValues()).containsEntry(REQUEST_ROUTE_KEY, "/handler/invalid-invite-malformed")
        assertThat(event.keyValues()).containsEntry(REQUEST_STATUS_KEY, "401")
        assertThat(event.keyValues()).containsEntry(INVITE_INVALID_REASON_KEY, "malformed")
    }

    @Test
    @WithMockUser
    fun `maps invite not found exception to 404 generic error with message`() {
        // act
        val result = mockMvc.get("/handler/invite-not-found")

        // assert
        result.andExpect {
            status { isNotFound() }
            view { name("generic_error") }
            model {
                attribute("error_message", ErrorMessages.INVITE_NOT_FOUND)
                attribute("error_details", ErrorMessages.INVITE_NOT_FOUND_DETAILS)
            }
        }

        val event =
            listAppender.list.first { it.formattedMessage.contains("InviteNotFoundException exception occurred") }
        assertThat(event.level).isEqualTo(Level.WARN)
        assertThat(event.keyValues()).containsEntry(REQUEST_ROUTE_KEY, "/handler/invite-not-found")
        assertThat(event.keyValues()).containsEntry(REQUEST_STATUS_KEY, "404")
        assertThat(event.keyValues()).containsEntry(INVITE_INVALID_REASON_KEY, "not_found")
    }

    @Test
    @WithMockUser
    fun `maps unknown exception to service unavailable with generic service message`() {
        // act
        val result = mockMvc.get("/handler/unknown")

        // assert
        result.andExpect {
            status { isServiceUnavailable() }
            view { name("generic_error") }
            model {
                attribute("error_message", ErrorMessages.SERVICE_TEMP_UNAVAILABLE)
                attribute("error_details", ErrorMessages.SERVICE_TEMP_UNAVAILABLE_DETAILS)
            }
        }

        val event = listAppender.list.first { it.formattedMessage == "Unknown exception handled at controller layer" }
        assertThat(event.level).isEqualTo(Level.WARN)
        assertThat(event.keyValues()).containsEntry(REQUEST_METHOD_KEY, "GET")
        assertThat(event.keyValues()).containsEntry(REQUEST_ROUTE_KEY, "/handler/unknown")
        assertThat(event.keyValues()).containsEntry(REQUEST_URI_KEY, "/handler/unknown")
        assertThat(event.keyValues()).containsEntry(REQUEST_STATUS_KEY, "503")
    }

    @Test
    @WithMockUser
    fun `logs keycloak handler request context for client and server errors`() {
        // act
        mockMvc.get("/handler/keycloak-4xx")
            .andExpect { status { isBadRequest() } }
        mockMvc.get("/handler/keycloak-5xx")
            .andExpect { status { isServiceUnavailable() } }

        // assert
        val events = listAppender.list.filter {
            it.formattedMessage == "Keycloak admin client exception handled at controller layer"
        }
        assertThat(events).hasSize(2)
        val client4xx = events.first { it.keyValues()[REQUEST_STATUS_KEY] == "400" }
        val server5xx = events.first { it.keyValues()[REQUEST_STATUS_KEY] == "503" }

        assertThat(client4xx.level).isEqualTo(Level.DEBUG) // deduped outer log for Keycloak exception
        assertThat(client4xx.keyValues()).containsEntry(REQUEST_METHOD_KEY, "GET")
        assertThat(client4xx.keyValues()).containsEntry(REQUEST_ROUTE_KEY, "/handler/keycloak-4xx")
        assertThat(client4xx.keyValues()).containsEntry(REQUEST_URI_KEY, "/handler/keycloak-4xx")

        assertThat(server5xx.level).isEqualTo(Level.DEBUG) // deduped outer log for Keycloak exception
        assertThat(server5xx.keyValues()).containsEntry(REQUEST_METHOD_KEY, "GET")
        assertThat(server5xx.keyValues()).containsEntry(REQUEST_ROUTE_KEY, "/handler/keycloak-5xx")
        assertThat(server5xx.keyValues()).containsEntry(REQUEST_URI_KEY, "/handler/keycloak-5xx")
    }
}

@Controller
class TestThrowingController {
    @GetMapping("/handler/keycloak-4xx")
    fun throwKeycloakClientError(): String = throw KeycloakAdminClientException("bad request", HttpStatus.BAD_REQUEST)

    @GetMapping("/handler/keycloak-5xx")
    fun throwKeycloakServerError(): String =
        throw KeycloakAdminClientException("server error", HttpStatus.INTERNAL_SERVER_ERROR)

    @GetMapping("/handler/invalid-invite")
    fun throwInvalidInvite(): String = throw InvalidInviteException(reason = InvalidInviteReason.EXPIRED)

    @GetMapping("/handler/invalid-invite-malformed")
    fun throwMalformedInvalidInvite(): String = throw InvalidInviteException(reason = InvalidInviteReason.MALFORMED)

    @GetMapping("/handler/invite-not-found")
    fun throwInviteNotFound(): String = throw InviteNotFoundException()

    @GetMapping("/handler/unknown")
    fun throwUnknown(): String {
        error("boom")
    }
}

private fun ILoggingEvent.keyValues(): Map<String, String> = (this.keyValuePairs ?: emptyList())
    .associate { it.key to (it.value?.toString() ?: "null") }
