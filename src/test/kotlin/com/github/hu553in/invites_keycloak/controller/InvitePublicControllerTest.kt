package com.github.hu553in.invites_keycloak.controller

import com.github.hu553in.invites_keycloak.InvitesKeycloakApplication
import com.github.hu553in.invites_keycloak.client.KeycloakAdminClient
import com.github.hu553in.invites_keycloak.config.TestClientRegistrationRepositoryConfig
import com.github.hu553in.invites_keycloak.entity.InviteEntity
import com.github.hu553in.invites_keycloak.exception.InvalidInviteException
import com.github.hu553in.invites_keycloak.exception.KeycloakAdminClientException
import com.github.hu553in.invites_keycloak.exception.handler.ControllerExceptionHandler
import com.github.hu553in.invites_keycloak.service.InviteService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.never
import org.mockito.BDDMockito.then
import org.mockito.BDDMockito.willThrow
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.*

@WebMvcTest(InvitePublicController::class)
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = [InvitesKeycloakApplication::class])
@Import(ControllerExceptionHandler::class, TestClientRegistrationRepositoryConfig::class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class InvitePublicControllerTest(
    private val mockMvc: MockMvc
) {

    private val clock = Clock.systemUTC()

    @MockitoBean
    private lateinit var inviteService: InviteService

    @MockitoBean
    private lateinit var keycloakAdminClient: KeycloakAdminClient

    @Test
    fun `validateInvite renders success view when invite processed`() {
        // arrange
        val invite = createInvite()

        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(false)
        given(keycloakAdminClient.createUser("master", invite.email, invite.email)).willReturn("user-id")
        given(inviteService.useOnce(invite.id!!)).willReturn(invite)

        // act
        val result = mockMvc.get("/invite/master/token")

        // assert
        result.andExpect {
            status { isOk() }
            view { name("public/account_created") }
        }

        then(inviteService).should().validateToken("master", "token")
        then(keycloakAdminClient).should().userExists("master", invite.email)
        then(keycloakAdminClient).should().createUser("master", invite.email, invite.email)
        then(keycloakAdminClient).should().assignRealmRoles("master", "user-id", invite.roles)
        then(keycloakAdminClient).should().executeActionsEmail("master", "user-id")
        then(inviteService).should().useOnce(invite.id!!)

        assertThat(result.andReturn().modelAndView?.viewName).isEqualTo("public/account_created")
    }

    @Test
    fun `validateInvite renders account exists view when user already present`() {
        // arrange
        val invite = createInvite()

        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(true)

        // act
        val result = mockMvc.get("/invite/master/token")

        // assert
        result.andExpect {
            status { isConflict() }
            view { name("public/generic_error") }
            model {
                attribute("error_message", "Account already exists")
                attribute(
                    "error_details",
                    "An account with passed data is already registered. " +
                        "If you believe this is an error, please contact your administrator."
                )
            }
        }

        then(inviteService).should().validateToken("master", "token")
        then(keycloakAdminClient).should().userExists("master", invite.email)
        then(keycloakAdminClient).shouldHaveNoMoreInteractions()
        then(inviteService).should(never()).useOnce(invite.id!!)
    }

    @Test
    fun `validateInvite renders generic error when invite invalid`() {
        // arrange
        given(inviteService.validateToken("master", "token")).willThrow(InvalidInviteException())

        // act
        val result = mockMvc.get("/invite/master/token")

        // assert
        result.andExpect {
            status { isUnauthorized() }
            view { name("public/generic_error") }
            model { attribute("error_message", "Invite is invalid") }
        }

        then(inviteService).should().validateToken("master", "token")
        then(keycloakAdminClient).shouldHaveNoInteractions()
    }

    @Test
    fun `validateInvite renders generic error when Keycloak API fails`() {
        // arrange
        val invite = createInvite()

        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(false)
        given(keycloakAdminClient.createUser("master", invite.email, invite.email))
            .willThrow(KeycloakAdminClientException("Keycloak unavailable"))

        // act
        val result = mockMvc.get("/invite/master/token")

        // assert
        result.andExpect {
            status { isServiceUnavailable() }
            view { name("public/generic_error") }
            model { attribute("error_message", "Service is not available") }
        }

        then(inviteService).should().validateToken("master", "token")
        then(keycloakAdminClient).should().userExists("master", invite.email)
        then(keycloakAdminClient).should().createUser("master", invite.email, invite.email)
        then(keycloakAdminClient).shouldHaveNoMoreInteractions()
        then(inviteService).should(never()).useOnce(invite.id!!)
    }

    @Test
    fun `validateInvite renders generic error when unexpected failure occurs`() {
        // arrange
        val invite = createInvite()

        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(false)
        given(keycloakAdminClient.createUser("master", invite.email, invite.email)).willReturn("user-id")
        willThrow(RuntimeException("boom"))
            .given(keycloakAdminClient)
            .assignRealmRoles("master", "user-id", invite.roles)

        // act
        val result = mockMvc.get("/invite/master/token")

        // assert
        result.andExpect {
            status { isInternalServerError() }
            view { name("public/generic_error") }
            model { attribute("error_message", "Unknown error") }
        }

        then(inviteService).should().validateToken("master", "token")
        then(keycloakAdminClient).should().userExists("master", invite.email)
        then(keycloakAdminClient).should().createUser("master", invite.email, invite.email)
        then(keycloakAdminClient).should().assignRealmRoles("master", "user-id", invite.roles)
        then(keycloakAdminClient).should(never()).executeActionsEmail("master", "user-id")
        then(inviteService).should(never()).useOnce(invite.id!!)
    }

    private fun createInvite(
        realm: String = "master",
        email: String = "user@example.com",
        roles: Set<String> = setOf("realmRole")
    ): InviteEntity {
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        val expiresAt = now.plusSeconds(3600)

        return InviteEntity(
            id = UUID.randomUUID(),
            realm = realm,
            tokenHash = "hash",
            salt = "salt",
            email = email,
            createdBy = "creator",
            createdAt = now,
            expiresAt = expiresAt,
            roles = roles
        )
    }
}
