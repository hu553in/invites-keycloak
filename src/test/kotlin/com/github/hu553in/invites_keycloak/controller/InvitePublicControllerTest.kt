package com.github.hu553in.invites_keycloak.controller

import com.github.hu553in.invites_keycloak.client.KeycloakAdminClient
import com.github.hu553in.invites_keycloak.config.TestClientRegistrationRepositoryConfig
import com.github.hu553in.invites_keycloak.entity.InviteEntity
import com.github.hu553in.invites_keycloak.exception.InvalidInviteException
import com.github.hu553in.invites_keycloak.exception.InvalidInviteReason
import com.github.hu553in.invites_keycloak.exception.InviteNotFoundException
import com.github.hu553in.invites_keycloak.exception.KeycloakAdminClientException
import com.github.hu553in.invites_keycloak.exception.handler.ControllerExceptionHandler
import com.github.hu553in.invites_keycloak.service.InviteService
import com.github.hu553in.invites_keycloak.util.ErrorMessages
import com.github.hu553in.invites_keycloak.util.SYSTEM_USER_ID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.mock
import org.mockito.BDDMockito.never
import org.mockito.BDDMockito.reset
import org.mockito.BDDMockito.then
import org.mockito.BDDMockito.verify
import org.mockito.BDDMockito.willThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.util.*

@WebMvcTest(InvitePublicController::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(
    ControllerExceptionHandler::class,
    TestClientRegistrationRepositoryConfig::class,
    InvitePublicControllerTest.TestConfig::class
)
class InvitePublicControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var inviteService: InviteService

    @Autowired
    private lateinit var keycloakAdminClient: KeycloakAdminClient

    @BeforeEach
    fun resetMocks() {
        reset(inviteService, keycloakAdminClient)
    }

    @TestConfiguration
    class TestConfig {
        @Bean
        @Primary
        fun inviteService(): InviteService = mock(InviteService::class.java)

        @Bean
        @Primary
        fun keycloakAdminClient(): KeycloakAdminClient = mock(KeycloakAdminClient::class.java)
    }

    @Test
    fun `validateInvite renders success view when invite processed`() {
        // arrange
        val invite = createInvite()

        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(false)
        given(keycloakAdminClient.createUser("master", invite.email, invite.email)).willReturn("user-id")

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
        then(keycloakAdminClient).should(never()).deleteUser("master", "user-id")
        then(inviteService).shouldHaveNoMoreInteractions()
    }

    @Test
    fun `validateInvite skips role assignment when invite has no roles`() {
        // arrange
        val invite = createInvite(roles = emptySet())

        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(false)
        given(keycloakAdminClient.createUser("master", invite.email, invite.email)).willReturn("user-id")

        // act
        val result = mockMvc.get("/invite/master/token")

        // assert
        result.andExpect {
            status { isOk() }
            view { name("public/account_created") }
        }
        then(keycloakAdminClient).should().userExists("master", invite.email)
        then(keycloakAdminClient).should().createUser("master", invite.email, invite.email)
        then(keycloakAdminClient).should().executeActionsEmail("master", "user-id")
        then(keycloakAdminClient).shouldHaveNoMoreInteractions()
        then(inviteService).should().useOnce(invite.id!!)
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
            view { name("generic_error") }
        }
        verify(keycloakAdminClient, never())
            .createUser("master", invite.email, invite.email)
        then(inviteService).should().revoke(invite.id!!, SYSTEM_USER_ID)
        then(inviteService).should(never()).useOnce(invite.id!!)
    }

    @Test
    fun `validateInvite renders generic error when invite invalid`() {
        // arrange
        given(inviteService.validateToken("master", "token"))
            .willThrow(InvalidInviteException(reason = InvalidInviteReason.EXPIRED))

        // act
        val result = mockMvc.get("/invite/master/token")

        // assert
        result.andExpect {
            status { isUnauthorized() }
            view { name("generic_error") }
        }
        then(keycloakAdminClient).shouldHaveNoInteractions()
    }

    @Test
    fun `validateInvite accepts trailing slash in invite url`() {
        // arrange
        given(inviteService.validateToken("master", "token"))
            .willThrow(InvalidInviteException(reason = InvalidInviteReason.EXPIRED))

        // act
        val result = mockMvc.get("/invite/master/token/")

        // assert
        result.andExpect {
            status { isUnauthorized() }
            view { name("generic_error") }
        }
        then(inviteService).should().validateToken("master", "token")
        then(keycloakAdminClient).shouldHaveNoInteractions()
    }

    @Test
    fun `validateInvite renders not found when invite is missing`() {
        // arrange
        given(inviteService.validateToken("master", "token"))
            .willThrow(InviteNotFoundException())

        // act
        val result = mockMvc.get("/invite/master/token")

        // assert
        result.andExpect {
            status { isNotFound() }
            view { name("generic_error") }
            model { attribute("error_message", "Invite is not found") }
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
            .willThrow(KeycloakAdminClientException("boom"))

        // act
        val result = mockMvc.get("/invite/master/token")

        // assert
        result.andExpect {
            status { isServiceUnavailable() }
            view { name("generic_error") }
        }
        then(inviteService).should().validateToken("master", "token")
        then(inviteService).shouldHaveNoMoreInteractions()
        then(keycloakAdminClient).should().userExists("master", invite.email)
        then(keycloakAdminClient).should().createUser("master", invite.email, invite.email)
        then(keycloakAdminClient).shouldHaveNoMoreInteractions()
    }

    @Test
    fun `validateInvite triggers rollback and revokes invite when assigning roles fails`() {
        // arrange
        val invite = createInvite()
        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(false)
        given(keycloakAdminClient.createUser("master", invite.email, invite.email)).willReturn("user-id")
        willThrow(IllegalStateException("role-assign-failed"))
            .given(keycloakAdminClient)
            .assignRealmRoles("master", "user-id", invite.roles)

        // act
        val result = mockMvc.get("/invite/master/token")

        // assert
        result.andExpect {
            status { isGone() }
            view { name("generic_error") }
            model { attribute("error_message", ErrorMessages.INVITE_NO_LONGER_VALID) }
        }
        then(keycloakAdminClient).should().deleteUser("master", "user-id")
        then(inviteService).should().revoke(invite.id!!, SYSTEM_USER_ID)
        verify(inviteService, never()).useOnce(invite.id!!)
    }

    @Test
    fun `validateInvite triggers rollback and revokes invite when execute actions fails`() {
        // arrange
        val invite = createInvite()
        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(false)
        given(keycloakAdminClient.createUser("master", invite.email, invite.email)).willReturn("user-id")
        willThrow(IllegalStateException("actions-failed"))
            .given(keycloakAdminClient)
            .executeActionsEmail("master", "user-id")

        // act
        val result = mockMvc.get("/invite/master/token")

        // assert
        result.andExpect {
            status { isGone() }
            view { name("generic_error") }
            model { attribute("error_message", ErrorMessages.INVITE_NO_LONGER_VALID) }
        }
        then(keycloakAdminClient).should().deleteUser("master", "user-id")
        then(inviteService).should().revoke(invite.id!!, SYSTEM_USER_ID)
        verify(inviteService, never()).useOnce(invite.id!!)
    }

    @Test
    fun `does not revoke invite on transient Keycloak failure`() {
        // arrange
        val invite = createInvite()
        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(false)
        val transient = KeycloakAdminClientException("temporary outage")
        given(keycloakAdminClient.createUser("master", invite.email, invite.email)).willThrow(transient)

        // act
        val result = mockMvc.get("/invite/master/token")

        // assert
        result.andExpect {
            status { isServiceUnavailable() }
            view { name("generic_error") }
            model { attribute("error_message", ErrorMessages.SERVICE_TEMP_UNAVAILABLE) }
        }
        then(inviteService).should().validateToken("master", "token")
        then(inviteService).shouldHaveNoMoreInteractions()
        then(keycloakAdminClient).should().userExists("master", invite.email)
        then(keycloakAdminClient).should().createUser("master", invite.email, invite.email)
        then(keycloakAdminClient).shouldHaveNoMoreInteractions()
    }

    @Test
    fun `revokes invite on Keycloak client error with created user`() {
        // arrange
        val invite = createInvite()
        val clientError = org.springframework.web.reactive.function.client.WebClientResponseException.create(
            400,
            "bad request",
            org.springframework.http.HttpHeaders(),
            ByteArray(0),
            null
        )
        val exception = KeycloakAdminClientException("role missing", clientError)

        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(false)
        given(keycloakAdminClient.createUser("master", invite.email, invite.email)).willReturn("user-id")
        willThrow(exception)
            .given(keycloakAdminClient)
            .assignRealmRoles("master", "user-id", invite.roles)

        // act
        val result = mockMvc.get("/invite/master/token")

        // assert
        result.andExpect {
            status { isGone() }
            view { name("generic_error") }
            model { attribute("error_message", ErrorMessages.INVITE_NO_LONGER_VALID) }
        }
        then(keycloakAdminClient).should().deleteUser("master", "user-id")
        then(inviteService).should().revoke(invite.id!!, SYSTEM_USER_ID)
        then(inviteService).should(never()).useOnce(invite.id!!)
    }

    @Test
    fun `revokes invite when createUser fails with conflict`() {
        // arrange
        val invite = createInvite()
        val conflict = org.springframework.web.reactive.function.client.WebClientResponseException.create(
            409,
            "conflict",
            org.springframework.http.HttpHeaders(),
            ByteArray(0),
            null
        )
        val exception = KeycloakAdminClientException("user exists", conflict)

        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(false)
        willThrow(exception)
            .given(keycloakAdminClient)
            .createUser("master", invite.email, invite.email)

        // act
        val result = mockMvc.get("/invite/master/token")

        // assert
        result.andExpect {
            status { isGone() }
            view { name("generic_error") }
            model { attribute("error_message", ErrorMessages.INVITE_NO_LONGER_VALID) }
        }
        then(inviteService).should().validateToken("master", "token")
        then(keycloakAdminClient).should().userExists("master", invite.email)
        then(keycloakAdminClient).should().createUser("master", invite.email, invite.email)
        then(inviteService).should().revoke(invite.id!!, SYSTEM_USER_ID)
        then(inviteService).shouldHaveNoMoreInteractions()
        then(keycloakAdminClient).shouldHaveNoMoreInteractions()
    }

    @Test
    fun `revokes invite when Keycloak client error is permanent without status`() {
        // arrange
        val invite = createInvite()
        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(false)
        given(keycloakAdminClient.createUser("master", invite.email, invite.email)).willReturn("user-id")
        willThrow(
            KeycloakAdminClientException("role is not found", org.springframework.http.HttpStatus.NOT_FOUND)
        )
            .given(keycloakAdminClient)
            .assignRealmRoles("master", "user-id", invite.roles)

        // act
        val result = mockMvc.get("/invite/master/token")

        // assert
        result.andExpect {
            status { isGone() }
            view { name("generic_error") }
            model { attribute("error_message", ErrorMessages.INVITE_NO_LONGER_VALID) }
        }
        then(keycloakAdminClient).should().deleteUser("master", "user-id")
        then(inviteService).should().revoke(invite.id!!, SYSTEM_USER_ID)
        then(inviteService).should(never()).useOnce(invite.id!!)
    }

    @Test
    fun `unknown exception returns 503 without creating user`() {
        // arrange
        given(inviteService.validateToken("master", "token")).willThrow(RuntimeException("boom"))

        // act
        val result = mockMvc.get("/invite/master/token")

        // assert
        result.andExpect {
            status { isServiceUnavailable() }
            view { name("generic_error") }
            model { attribute("error_message", ErrorMessages.SERVICE_TEMP_UNAVAILABLE) }
        }
        then(inviteService).should().validateToken("master", "token")
        then(inviteService).shouldHaveNoMoreInteractions()
        then(keycloakAdminClient).shouldHaveNoInteractions()
    }

    private fun createInvite(
        realm: String = "master",
        email: String = "user@example.com",
        roles: Set<String> = setOf("realmRole")
    ): InviteEntity {
        val now = Instant.parse("2025-01-01T00:00:00Z")
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
            maxUses = 1,
            roles = roles
        )
    }
}
