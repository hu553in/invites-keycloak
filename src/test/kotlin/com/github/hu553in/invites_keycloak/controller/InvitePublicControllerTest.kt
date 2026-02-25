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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.mock
import org.mockito.BDDMockito.never
import org.mockito.BDDMockito.reset
import org.mockito.BDDMockito.then
import org.mockito.BDDMockito.verify
import org.mockito.BDDMockito.willThrow
import org.mockito.Mockito.times
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@WebMvcTest(InvitePublicController::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(
    ControllerExceptionHandler::class,
    TestClientRegistrationRepositoryConfig::class,
    InvitePublicControllerTest.TestConfig::class
)
@Suppress("LargeClass")
class InvitePublicControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var inviteService: InviteService

    @Autowired
    private lateinit var keycloakAdminClient: KeycloakAdminClient

    @Autowired
    private lateinit var clock: Clock

    @BeforeEach
    fun resetMocks() {
        reset(inviteService, keycloakAdminClient)
        (clock as MutableClock).set(Instant.parse("2025-01-01T00:00:00Z"))
    }

    @TestConfiguration
    class TestConfig {
        @Bean
        @Primary
        fun inviteService(): InviteService = mock(InviteService::class.java)

        @Bean
        @Primary
        fun keycloakAdminClient(): KeycloakAdminClient = mock(KeycloakAdminClient::class.java)

        @Bean
        @Primary
        fun clock(): Clock = MutableClock(Instant.parse("2025-01-01T00:00:00Z"))
    }

    @Test
    fun `validateInvite renders confirmation view and does not trigger side effects`() {
        // arrange
        val invite = createInvite()
        given(inviteService.validateToken("master", "token")).willReturn(invite)

        // act
        val result = mockMvc.get("/invite/master/token")

        // assert
        result.andExpect {
            status { isOk() }
            view { name("public/invite_confirm") }
            model {
                attribute("realm", "master")
                attribute("token", "token")
                attributeExists("redeemChallenge")
            }
        }

        val mvcResult = result.andReturn()
        val response = mvcResult.response
        val challenge = mvcResult.modelAndView!!.model["redeemChallenge"] as String
        assertThat(challenge).isNotBlank()
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store")
        assertThat(response.getHeader(HttpHeaders.PRAGMA)).isEqualTo("no-cache")
        assertThat(response.getHeader("X-Robots-Tag")).isEqualTo("noindex, nofollow")

        then(inviteService).should().validateToken("master", "token")
        then(inviteService).shouldHaveNoMoreInteractions()
        then(keycloakAdminClient).shouldHaveNoInteractions()
    }

    @Test
    fun `invite success page renders account created view without side effects`() {
        // act
        val result = mockMvc.get("/invite/success")

        // assert
        result.andExpect {
            status { isOk() }
            view { name("public/account_created") }
        }
        then(inviteService).shouldHaveNoInteractions()
        then(keycloakAdminClient).shouldHaveNoInteractions()
    }

    @Test
    fun `validateInvite accepts trailing slash in invite url`() {
        // arrange
        val invite = createInvite()
        given(inviteService.validateToken("master", "token")).willReturn(invite)

        // act
        val result = mockMvc.get("/invite/master/token/")

        // assert
        result.andExpect {
            status { isOk() }
            view { name("public/invite_confirm") }
            model { attributeExists("redeemChallenge") }
        }
        then(inviteService).should().validateToken("master", "token")
        then(inviteService).shouldHaveNoMoreInteractions()
        then(keycloakAdminClient).shouldHaveNoInteractions()
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
            model { attribute("error_message", ErrorMessages.INVITE_NOT_FOUND) }
        }
        then(inviteService).should().validateToken("master", "token")
        then(keycloakAdminClient).shouldHaveNoInteractions()
    }

    @Test
    fun `unknown exception returns 503 without creating user on confirmation GET`() {
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

    @Test
    fun `redeemInvite renders success view when invite processed`() {
        // arrange
        val invite = createInvite()
        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(false)
        given(keycloakAdminClient.createUser("master", invite.email, invite.email)).willReturn("user-id")
        val confirm = openConfirmPage()

        // act
        val result = redeemInvite(session = confirm.session, challenge = confirm.challenge)

        // assert
        result.andExpect {
            status { is3xxRedirection() }
        }
        assertThat(result.andReturn().response.redirectedUrl).isEqualTo("/invite/success")
        then(inviteService).should(times(2)).validateToken("master", "token")
        then(keycloakAdminClient).should().userExists("master", invite.email)
        then(keycloakAdminClient).should().createUser("master", invite.email, invite.email)
        then(keycloakAdminClient).should().assignRealmRoles("master", "user-id", invite.roles)
        then(keycloakAdminClient).should().executeActionsEmail("master", "user-id")
        then(inviteService).should().useOnce(invite.id!!)
        then(keycloakAdminClient).should(never()).deleteUser("master", "user-id")
        then(inviteService).shouldHaveNoMoreInteractions()
    }

    @Test
    fun `redeemInvite accepts trailing slash in invite url`() {
        // arrange
        val invite = createInvite()
        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(false)
        given(keycloakAdminClient.createUser("master", invite.email, invite.email)).willReturn("user-id")
        val confirm = openConfirmPage(path = "/invite/master/token/")

        // act
        val result =
            redeemInvite(path = "/invite/master/token/", session = confirm.session, challenge = confirm.challenge)

        // assert
        result.andExpect {
            status { is3xxRedirection() }
        }
        assertThat(result.andReturn().response.redirectedUrl).isEqualTo("/invite/success")
        then(inviteService).should(times(2)).validateToken("master", "token")
        then(inviteService).should().useOnce(invite.id!!)
    }

    @Test
    fun `redeemInvite skips role assignment when invite has no roles`() {
        // arrange
        val invite = createInvite(roles = emptySet())
        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(false)
        given(keycloakAdminClient.createUser("master", invite.email, invite.email)).willReturn("user-id")
        val confirm = openConfirmPage()

        // act
        val result = redeemInvite(session = confirm.session, challenge = confirm.challenge)

        // assert
        result.andExpect {
            status { is3xxRedirection() }
        }
        assertThat(result.andReturn().response.redirectedUrl).isEqualTo("/invite/success")
        then(keycloakAdminClient).should().userExists("master", invite.email)
        then(keycloakAdminClient).should().createUser("master", invite.email, invite.email)
        then(keycloakAdminClient).should().executeActionsEmail("master", "user-id")
        then(keycloakAdminClient).shouldHaveNoMoreInteractions()
        then(inviteService).should(times(2)).validateToken("master", "token")
        then(inviteService).should().useOnce(invite.id!!)
        then(inviteService).shouldHaveNoMoreInteractions()
    }

    @Test
    fun `redeemInvite renders account exists view when user already present`() {
        // arrange
        val invite = createInvite()
        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(true)
        val confirm = openConfirmPage()

        // act
        val result = redeemInvite(session = confirm.session, challenge = confirm.challenge)

        // assert
        result.andExpect {
            status { isConflict() }
            view { name("generic_error") }
        }
        verify(keycloakAdminClient, never())
            .createUser("master", invite.email, invite.email)
        then(inviteService).should(times(2)).validateToken("master", "token")
        then(inviteService).should().revoke(invite.id!!, SYSTEM_USER_ID)
        then(inviteService).should(never()).useOnce(invite.id!!)
    }

    @Test
    fun `redeemInvite rejects missing confirmation challenge`() {
        // arrange

        // act
        val result = redeemInvite(session = MockHttpSession(), challenge = null)

        // assert
        result.andExpect {
            status { isUnauthorized() }
            view { name("generic_error") }
            model {
                attribute("error_message", ErrorMessages.INVITE_CONFIRMATION_INVALID)
                attribute("error_details", ErrorMessages.INVITE_CONFIRMATION_INVALID_DETAILS)
            }
        }
        then(inviteService).shouldHaveNoInteractions()
        then(keycloakAdminClient).shouldHaveNoInteractions()
    }

    @Test
    fun `redeemInvite rejects invalid confirmation challenge`() {
        // arrange

        // act
        val result = redeemInvite(session = MockHttpSession(), challenge = "missing")

        // assert
        result.andExpect {
            status { isUnauthorized() }
            view { name("generic_error") }
            model {
                attribute("error_message", ErrorMessages.INVITE_CONFIRMATION_INVALID)
                attribute("error_details", ErrorMessages.INVITE_CONFIRMATION_INVALID_DETAILS)
            }
        }
        then(inviteService).shouldHaveNoInteractions()
        then(keycloakAdminClient).shouldHaveNoInteractions()
    }

    @Test
    fun `redeemInvite rejects reused confirmation challenge`() {
        // arrange
        val invite = createInvite()
        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(false)
        given(keycloakAdminClient.createUser("master", invite.email, invite.email)).willReturn("user-id")
        val confirm = openConfirmPage()

        // act
        val first = redeemInvite(session = confirm.session, challenge = confirm.challenge)
        val second = redeemInvite(session = confirm.session, challenge = confirm.challenge)

        // assert
        first.andExpect {
            status { is3xxRedirection() }
        }
        assertThat(first.andReturn().response.redirectedUrl).isEqualTo("/invite/success")
        second.andExpect {
            status { isUnauthorized() }
            view { name("generic_error") }
            model {
                attribute("error_message", ErrorMessages.INVITE_CONFIRMATION_INVALID)
                attribute("error_details", ErrorMessages.INVITE_CONFIRMATION_INVALID_DETAILS)
            }
        }
        then(inviteService).should(times(2)).validateToken("master", "token")
        then(inviteService).should().useOnce(invite.id!!)
        then(inviteService).shouldHaveNoMoreInteractions()
    }

    @Test
    fun `latest confirmation page invalidates previous challenge for same invite in session`() {
        // arrange
        val invite = createInvite()
        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(false)
        given(keycloakAdminClient.createUser("master", invite.email, invite.email)).willReturn("user-id")
        val firstConfirm = openConfirmPage()
        val latestConfirm = openConfirmPage(session = firstConfirm.session)

        // act
        val oldChallengeResult = redeemInvite(session = firstConfirm.session, challenge = firstConfirm.challenge)
        val latestChallengeResult = redeemInvite(session = firstConfirm.session, challenge = latestConfirm.challenge)

        // assert
        oldChallengeResult.andExpect {
            status { isUnauthorized() }
            view { name("generic_error") }
            model {
                attribute("error_message", ErrorMessages.INVITE_CONFIRMATION_INVALID)
                attribute("error_details", ErrorMessages.INVITE_CONFIRMATION_INVALID_DETAILS)
            }
        }
        latestChallengeResult.andExpect {
            status { is3xxRedirection() }
        }
        assertThat(latestChallengeResult.andReturn().response.redirectedUrl).isEqualTo("/invite/success")
        then(inviteService).should(times(3)).validateToken("master", "token")
        then(inviteService).should().useOnce(invite.id!!)
        then(inviteService).shouldHaveNoMoreInteractions()
    }

    @Test
    fun `validateInvite rejects issuing challenge while redeem is in progress for same invite in session`() {
        // arrange
        val invite = createInvite()
        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(false)
        val createUserStarted = CountDownLatch(1)
        val allowCreateUserToFinish = CountDownLatch(1)
        given(keycloakAdminClient.createUser("master", invite.email, invite.email)).willAnswer {
            createUserStarted.countDown()
            check(allowCreateUserToFinish.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release createUser" }
            "user-id"
        }
        val confirm = openConfirmPage()

        // act
        val redeemFuture = CompletableFuture.supplyAsync {
            redeemInvite(session = confirm.session, challenge = confirm.challenge).andReturn().response
        }
        assertThat(createUserStarted.await(5, TimeUnit.SECONDS)).isTrue()
        val getDuringRedeem = mockMvc.get("/invite/master/token") {
            session = confirm.session
        }
        allowCreateUserToFinish.countDown()
        val redeemResponse = redeemFuture.get(5, TimeUnit.SECONDS)

        // assert
        getDuringRedeem.andExpect {
            status { isConflict() }
            view { name("generic_error") }
            model {
                attribute("error_message", ErrorMessages.INVITE_CONFIRMATION_INVALID)
                attribute("error_details", ErrorMessages.INVITE_CONFIRMATION_INVALID_DETAILS)
            }
        }
        assertThat(redeemResponse.redirectedUrl).isEqualTo("/invite/success")
        then(inviteService).should(times(3)).validateToken("master", "token")
        then(inviteService).should().useOnce(invite.id!!)
    }

    @Test
    fun `redeemInvite rejects challenge that belongs to another invite`() {
        // arrange
        val inviteA = createInvite(email = "a@example.com")
        val inviteB = createInvite(email = "b@example.com")
        given(inviteService.validateToken("master", "token-a")).willReturn(inviteA)
        given(inviteService.validateToken("master", "token-b")).willReturn(inviteB)
        val confirm = openConfirmPage(path = "/invite/master/token-a")

        // act
        val result =
            redeemInvite(path = "/invite/master/token-b", session = confirm.session, challenge = confirm.challenge)

        // assert
        result.andExpect {
            status { isUnauthorized() }
            view { name("generic_error") }
            model {
                attribute("error_message", ErrorMessages.INVITE_CONFIRMATION_INVALID)
                attribute("error_details", ErrorMessages.INVITE_CONFIRMATION_INVALID_DETAILS)
            }
        }
        then(inviteService).should().validateToken("master", "token-a")
        then(inviteService).should().validateToken("master", "token-b")
        then(inviteService).shouldHaveNoMoreInteractions()
        then(keycloakAdminClient).shouldHaveNoInteractions()
    }

    @Test
    fun `redeemInvite rejects expired confirmation challenge`() {
        // arrange
        val invite = createInvite()
        given(inviteService.validateToken("master", "token")).willReturn(invite)
        val confirm = openConfirmPage()
        (clock as MutableClock).advanceSeconds(11 * 60)

        // act
        val result = redeemInvite(session = confirm.session, challenge = confirm.challenge)

        // assert
        result.andExpect {
            status { isUnauthorized() }
            view { name("generic_error") }
            model {
                attribute("error_message", ErrorMessages.INVITE_CONFIRMATION_INVALID)
                attribute("error_details", ErrorMessages.INVITE_CONFIRMATION_INVALID_DETAILS)
            }
        }
        then(inviteService).should().validateToken("master", "token")
        then(inviteService).shouldHaveNoMoreInteractions()
        then(keycloakAdminClient).shouldHaveNoInteractions()
    }

    @Test
    fun `redeemInvite evicts oldest confirmation challenge when session capacity exceeded`() {
        // arrange
        val invite = createInvite()
        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(false)
        given(keycloakAdminClient.createUser("master", invite.email, invite.email)).willReturn("user-id")

        val first = openConfirmPage()
        var latest = first
        repeat(40) {
            latest = openConfirmPage(session = first.session)
        }

        // act
        val evicted = redeemInvite(session = first.session, challenge = first.challenge)
        val valid = redeemInvite(session = first.session, challenge = latest.challenge)

        // assert
        evicted.andExpect {
            status { isUnauthorized() }
            view { name("generic_error") }
            model {
                attribute("error_message", ErrorMessages.INVITE_CONFIRMATION_INVALID)
                attribute("error_details", ErrorMessages.INVITE_CONFIRMATION_INVALID_DETAILS)
            }
        }
        valid.andExpect {
            status { is3xxRedirection() }
        }
        assertThat(valid.andReturn().response.redirectedUrl).isEqualTo("/invite/success")
        then(inviteService).should().useOnce(invite.id!!)
    }

    @Test
    fun `redeemInvite renders generic error when invite invalid after confirmation page`() {
        // arrange
        val invite = createInvite()
        given(inviteService.validateToken("master", "token"))
            .willReturn(invite)
            .willThrow(InvalidInviteException(reason = InvalidInviteReason.EXPIRED))
        val confirm = openConfirmPage()

        // act
        val result = redeemInvite(session = confirm.session, challenge = confirm.challenge)

        // assert
        result.andExpect {
            status { isUnauthorized() }
            view { name("generic_error") }
        }
        then(inviteService).should(times(2)).validateToken("master", "token")
        then(inviteService).shouldHaveNoMoreInteractions()
        then(keycloakAdminClient).shouldHaveNoInteractions()
    }

    @Test
    fun `redeemInvite renders not found when invite missing after confirmation page`() {
        // arrange
        val invite = createInvite()
        given(inviteService.validateToken("master", "token"))
            .willReturn(invite)
            .willThrow(InviteNotFoundException())
        val confirm = openConfirmPage()

        // act
        val result = redeemInvite(session = confirm.session, challenge = confirm.challenge)

        // assert
        result.andExpect {
            status { isNotFound() }
            view { name("generic_error") }
            model { attribute("error_message", ErrorMessages.INVITE_NOT_FOUND) }
        }
        then(inviteService).should(times(2)).validateToken("master", "token")
        then(inviteService).shouldHaveNoMoreInteractions()
        then(keycloakAdminClient).shouldHaveNoInteractions()
    }

    @Test
    fun `redeemInvite renders generic error when Keycloak API fails`() {
        // arrange
        val invite = createInvite()
        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(false)
        given(keycloakAdminClient.createUser("master", invite.email, invite.email))
            .willThrow(KeycloakAdminClientException("boom"))
        val confirm = openConfirmPage()

        // act
        val result = redeemInvite(session = confirm.session, challenge = confirm.challenge)

        // assert
        result.andExpect {
            status { isServiceUnavailable() }
            view { name("generic_error") }
        }
        then(inviteService).should(times(2)).validateToken("master", "token")
        then(inviteService).shouldHaveNoMoreInteractions()
        then(keycloakAdminClient).should().userExists("master", invite.email)
        then(keycloakAdminClient).should().createUser("master", invite.email, invite.email)
        then(keycloakAdminClient).shouldHaveNoMoreInteractions()
    }

    @Test
    fun `redeemInvite triggers rollback and revokes invite when assigning roles fails`() {
        // arrange
        val invite = createInvite()
        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(false)
        given(keycloakAdminClient.createUser("master", invite.email, invite.email)).willReturn("user-id")
        willThrow(IllegalStateException("role-assign-failed"))
            .given(keycloakAdminClient)
            .assignRealmRoles("master", "user-id", invite.roles)
        val confirm = openConfirmPage()

        // act
        val result = redeemInvite(session = confirm.session, challenge = confirm.challenge)

        // assert
        result.andExpect {
            status { isGone() }
            view { name("generic_error") }
            model { attribute("error_message", ErrorMessages.INVITE_NO_LONGER_VALID) }
        }
        then(keycloakAdminClient).should().deleteUser("master", "user-id")
        then(inviteService).should(times(2)).validateToken("master", "token")
        then(inviteService).should().revoke(invite.id!!, SYSTEM_USER_ID)
        verify(inviteService, never()).useOnce(invite.id!!)
    }

    @Test
    fun `redeemInvite triggers rollback and revokes invite when execute actions fails`() {
        // arrange
        val invite = createInvite()
        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(false)
        given(keycloakAdminClient.createUser("master", invite.email, invite.email)).willReturn("user-id")
        willThrow(IllegalStateException("actions-failed"))
            .given(keycloakAdminClient)
            .executeActionsEmail("master", "user-id")
        val confirm = openConfirmPage()

        // act
        val result = redeemInvite(session = confirm.session, challenge = confirm.challenge)

        // assert
        result.andExpect {
            status { isGone() }
            view { name("generic_error") }
            model { attribute("error_message", ErrorMessages.INVITE_NO_LONGER_VALID) }
        }
        then(keycloakAdminClient).should().deleteUser("master", "user-id")
        then(inviteService).should(times(2)).validateToken("master", "token")
        then(inviteService).should().revoke(invite.id!!, SYSTEM_USER_ID)
        verify(inviteService, never()).useOnce(invite.id!!)
    }

    @Test
    fun `does not revoke invite on transient Keycloak failure during redeem`() {
        // arrange
        val invite = createInvite()
        given(inviteService.validateToken("master", "token")).willReturn(invite)
        given(keycloakAdminClient.userExists("master", invite.email)).willReturn(false)
        val transient = KeycloakAdminClientException("temporary outage")
        given(keycloakAdminClient.createUser("master", invite.email, invite.email)).willThrow(transient)
        val confirm = openConfirmPage()

        // act
        val result = redeemInvite(session = confirm.session, challenge = confirm.challenge)

        // assert
        result.andExpect {
            status { isServiceUnavailable() }
            view { name("generic_error") }
            model { attribute("error_message", ErrorMessages.SERVICE_TEMP_UNAVAILABLE) }
        }
        then(inviteService).should(times(2)).validateToken("master", "token")
        then(inviteService).shouldHaveNoMoreInteractions()
        then(keycloakAdminClient).should().userExists("master", invite.email)
        then(keycloakAdminClient).should().createUser("master", invite.email, invite.email)
        then(keycloakAdminClient).shouldHaveNoMoreInteractions()
    }

    @Test
    fun `revokes invite on Keycloak client error with created user during redeem`() {
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
        val confirm = openConfirmPage()

        // act
        val result = redeemInvite(session = confirm.session, challenge = confirm.challenge)

        // assert
        result.andExpect {
            status { isGone() }
            view { name("generic_error") }
            model { attribute("error_message", ErrorMessages.INVITE_NO_LONGER_VALID) }
        }
        then(keycloakAdminClient).should().deleteUser("master", "user-id")
        then(inviteService).should(times(2)).validateToken("master", "token")
        then(inviteService).should().revoke(invite.id!!, SYSTEM_USER_ID)
        then(inviteService).should(never()).useOnce(invite.id!!)
    }

    @Test
    fun `revokes invite when createUser fails with conflict during redeem`() {
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
        val confirm = openConfirmPage()

        // act
        val result = redeemInvite(session = confirm.session, challenge = confirm.challenge)

        // assert
        result.andExpect {
            status { isGone() }
            view { name("generic_error") }
            model { attribute("error_message", ErrorMessages.INVITE_NO_LONGER_VALID) }
        }
        then(inviteService).should(times(2)).validateToken("master", "token")
        then(keycloakAdminClient).should().userExists("master", invite.email)
        then(keycloakAdminClient).should().createUser("master", invite.email, invite.email)
        then(inviteService).should().revoke(invite.id!!, SYSTEM_USER_ID)
        then(inviteService).shouldHaveNoMoreInteractions()
        then(keycloakAdminClient).shouldHaveNoMoreInteractions()
    }

    @Test
    fun `revokes invite when Keycloak client error is permanent without status during redeem`() {
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
        val confirm = openConfirmPage()

        // act
        val result = redeemInvite(session = confirm.session, challenge = confirm.challenge)

        // assert
        result.andExpect {
            status { isGone() }
            view { name("generic_error") }
            model { attribute("error_message", ErrorMessages.INVITE_NO_LONGER_VALID) }
        }
        then(keycloakAdminClient).should().deleteUser("master", "user-id")
        then(inviteService).should(times(2)).validateToken("master", "token")
        then(inviteService).should().revoke(invite.id!!, SYSTEM_USER_ID)
        then(inviteService).should(never()).useOnce(invite.id!!)
    }

    private fun openConfirmPage(
        path: String = "/invite/master/token",
        session: MockHttpSession? = null
    ): ConfirmPage {
        val result = if (session == null) {
            mockMvc.get(path).andReturn()
        } else {
            mockMvc.get(path) {
                this.session = session
            }.andReturn()
        }
        val resolvedSession = result.request.session as MockHttpSession
        val challenge = checkNotNull(result.modelAndView?.model?.get("redeemChallenge") as? String)
        return ConfirmPage(resolvedSession, challenge)
    }

    private fun redeemInvite(
        path: String = "/invite/master/token",
        session: MockHttpSession,
        challenge: String?
    ) = mockMvc.post(path) {
        this.session = session
        if (challenge != null) {
            param("challenge", challenge)
        }
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

    private data class ConfirmPage(
        val session: MockHttpSession,
        val challenge: String
    )

    private class MutableClock(initial: Instant) : Clock() {
        private var current: Instant = initial

        fun set(value: Instant) {
            current = value
        }

        fun advanceSeconds(seconds: Long) {
            current = current.plusSeconds(seconds)
        }

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current
    }
}
