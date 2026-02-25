package com.github.hu553in.invites_keycloak.config.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.hu553in.invites_keycloak.config.TestClientRegistrationRepositoryConfig
import com.github.hu553in.invites_keycloak.exception.handler.ControllerExceptionHandler
import com.github.hu553in.invites_keycloak.util.REQUEST_PATH_KEY
import com.github.hu553in.invites_keycloak.util.REQUEST_STATUS_KEY
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@WebMvcTest(
    controllers = [AccessLoggingFilterTest.TestLoggingController::class],
    properties = ["access-logging.enabled=true"]
)
@AutoConfigureMockMvc(addFilters = true)
@Import(
    AccessLoggingFilter::class,
    ControllerExceptionHandler::class,
    TestClientRegistrationRepositoryConfig::class,
    AccessLoggingFilterTest.TestLoggingController::class
)
@ActiveProfiles("test")
class AccessLoggingFilterTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    private lateinit var listAppender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger(AccessLoggingFilter::class.java) as Logger
        listAppender = ListAppender()
        listAppender.start()
        logger.addAppender(listAppender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(listAppender)
    }

    @Test
    @WithMockUser(username = "alice")
    fun `logs request path using route pattern`() {
        // arrange
        val realm = "realm-123"
        val token = "token-abc"

        // act
        mockMvc.get("/access-log/realm-123/token-abc") {
            accept = MediaType.TEXT_PLAIN
        }
            // assert
            .andExpect {
                status { isOk() }
            }

        val events = listAppender.list
        val loggedPaths = events
            .flatMap { it.keyValuePairs ?: emptyList() }
            .filter { it.key == REQUEST_PATH_KEY }
            .mapNotNull { it.value?.toString() }

        assertThat(loggedPaths)
            .describedAs("Logged paths should contain route pattern instead of raw values: %s", loggedPaths)
            .contains("/access-log/{realm}/{token}")
        assertThat(loggedPaths.any { it.contains(realm) || it.contains(token) })
            .isFalse()
        assertThat(events.map { it.level }).contains(Level.INFO)
    }

    @Test
    @WithMockUser(username = "alice")
    fun `logs handled client error response at warn level`() {
        // act
        mockMvc.get("/access-log/missing") {
            accept = MediaType.TEXT_PLAIN
        }
            // assert
            .andExpect {
                status { isNotFound() }
            }

        val accessEvent = listAppender.list.first { it.formattedMessage == "HTTP request completed with client error" }
        val requestStatuses = (accessEvent.keyValuePairs ?: emptyList())
            .filter { it.key == REQUEST_STATUS_KEY }
            .mapNotNull { it.value?.toString() }
        val requestPaths = (accessEvent.keyValuePairs ?: emptyList())
            .filter { it.key == REQUEST_PATH_KEY }
            .mapNotNull { it.value?.toString() }

        assertThat(accessEvent.level).isEqualTo(Level.WARN)
        assertThat(requestStatuses).contains("404")
        assertThat(requestPaths).contains("/access-log/missing")
    }

    @Test
    @WithMockUser(username = "alice")
    fun `logs handled server error response at error level`() {
        // act
        mockMvc.get("/access-log/fail") {
            accept = MediaType.TEXT_PLAIN
        }
            // assert
            .andExpect {
                status { isServiceUnavailable() }
            }

        val accessEvent = listAppender.list.first { it.formattedMessage == "HTTP request completed with server error" }
        val requestStatuses = (accessEvent.keyValuePairs ?: emptyList())
            .filter { it.key == REQUEST_STATUS_KEY }
            .mapNotNull { it.value?.toString() }
        val requestPaths = (accessEvent.keyValuePairs ?: emptyList())
            .filter { it.key == REQUEST_PATH_KEY }
            .mapNotNull { it.value?.toString() }

        assertThat(accessEvent.level).isEqualTo(Level.ERROR)
        assertThat(requestStatuses).contains("503")
        assertThat(requestPaths).contains("/access-log/fail")
    }

    @Test
    @WithMockUser(username = "alice")
    fun `logs failed request with inferred 500 status`() {
        // arrange
        val filter = AccessLoggingFilter()
        val request = MockHttpServletRequest("GET", "/access-log/fail")
        val response = MockHttpServletResponse()
        val chain = FilterChain { _, _ -> throw IllegalStateException("boom") }

        // act
        assertThatThrownBy {
            filter.doFilter(request, response, chain)
        }
            // assert
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("boom")

        val events = listAppender.list
        val requestPaths = events
            .flatMap { it.keyValuePairs ?: emptyList() }
            .filter { it.key == REQUEST_PATH_KEY }
            .mapNotNull { it.value?.toString() }
        val requestStatuses = events
            .flatMap { it.keyValuePairs ?: emptyList() }
            .filter { it.key == REQUEST_STATUS_KEY }
            .mapNotNull { it.value?.toString() }

        assertThat(events.map { it.formattedMessage }).contains("HTTP request failed")
        val failureEvent = events.first { it.formattedMessage == "HTTP request failed" }
        assertThat(failureEvent.level).isEqualTo(Level.ERROR)
        assertThat(requestPaths).contains("/access-log/fail")
        assertThat(requestStatuses).contains("500")
    }

    @RestController
    class TestLoggingController {
        @GetMapping("/access-log/{realm}/{token}")
        fun accessLog(@PathVariable realm: String, @PathVariable token: String): String {
            return "ok-$realm-$token"
        }

        @GetMapping("/access-log/fail")
        fun fail(): String {
            error("boom")
        }
    }
}
