package com.github.hu553in.invites_keycloak.config.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.hu553in.invites_keycloak.config.TestClientRegistrationRepositoryConfig
import com.github.hu553in.invites_keycloak.util.CURRENT_USER_ID_KEY
import com.github.hu553in.invites_keycloak.util.CURRENT_USER_SUBJECT_KEY
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@WebMvcTest(CurrentUserLoggingFilterTest.TestLoggingController::class)
@AutoConfigureMockMvc(addFilters = true)
@Import(
    CurrentUserLoggingFilter::class,
    TestClientRegistrationRepositoryConfig::class,
    CurrentUserLoggingFilterTest.TestLoggingController::class
)
@ActiveProfiles("test")
class CurrentUserLoggingFilterTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    private lateinit var listAppender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger(TestLoggingController::class.java) as Logger
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
    fun `request logs include current user id from MDC`() {
        // act
        mockMvc.get("/logging-test") {
            accept = MediaType.TEXT_PLAIN
        }
            // assert
            .andExpect {
                status { isOk() }
            }

        val events = listAppender.list
        val mdcValues = events.map { it.mdcPropertyMap[CURRENT_USER_ID_KEY] }
        assertThat(events).isNotEmpty
        assertThat(mdcValues)
            .describedAs("MDC values captured in log events: %s", mdcValues)
            .contains("alice")
        assertThat(events.map { it.formattedMessage }).contains("hello from controller")
    }

    @Test
    fun `request logs include subject when OIDC principal available`() {
        // arrange
        val idToken = OidcIdToken(
            "token",
            java.time.Instant.now(),
            java.time.Instant.now().plusSeconds(60),
            mapOf("sub" to "user-subject-123", "preferred_username" to "alice")
        )
        val authorities = listOf(SimpleGrantedAuthority("ROLE_USER"))
        val oidcUser = DefaultOidcUser(authorities, idToken, "preferred_username")
        val auth = OAuth2AuthenticationToken(oidcUser, authorities, "keycloak")

        // act
        mockMvc.get("/logging-test") {
            accept = MediaType.TEXT_PLAIN
            with(authentication(auth))
        }
            // assert
            .andExpect {
                status { isOk() }
            }

        val events = listAppender.list
        val subjects = events.map { it.mdcPropertyMap[CURRENT_USER_SUBJECT_KEY] }
        assertThat(subjects)
            .describedAs("MDC subjects captured in log events: %s", subjects)
            .contains("user-subject-123")
    }

    @RestController
    class TestLoggingController {
        private val log = LoggerFactory.getLogger(javaClass)

        @GetMapping("/logging-test")
        fun logSomething(): String {
            log.info("hello from controller")
            return "ok"
        }
    }
}
