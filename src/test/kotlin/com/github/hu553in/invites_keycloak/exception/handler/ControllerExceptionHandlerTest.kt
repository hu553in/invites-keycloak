package com.github.hu553in.invites_keycloak.exception.handler

import com.github.hu553in.invites_keycloak.config.TestClientRegistrationRepositoryConfig
import com.github.hu553in.invites_keycloak.exception.KeycloakAdminClientException
import com.github.hu553in.invites_keycloak.util.ErrorMessages
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
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
    TestClientRegistrationRepositoryConfig::class
)
@ActiveProfiles("test")
class ControllerExceptionHandlerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

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
                attribute("error_message", ErrorMessages.INVITE_CANNOT_BE_COMPLETED)
                attribute("error_details", ErrorMessages.INVITE_CANNOT_BE_COMPLETED_DETAILS)
            }
        }
    }
}

@Controller
class TestThrowingController {
    @GetMapping("/handler/keycloak-4xx")
    fun throwKeycloakClientError(): String {
        throw KeycloakAdminClientException("bad request", HttpStatus.BAD_REQUEST)
    }
}
