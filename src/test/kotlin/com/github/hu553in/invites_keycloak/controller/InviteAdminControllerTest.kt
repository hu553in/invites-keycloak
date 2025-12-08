package com.github.hu553in.invites_keycloak.controller

import com.github.hu553in.invites_keycloak.client.KeycloakAdminClient
import com.github.hu553in.invites_keycloak.config.TestClientRegistrationRepositoryConfig
import com.github.hu553in.invites_keycloak.config.props.InviteProps
import com.github.hu553in.invites_keycloak.entity.InviteEntity
import com.github.hu553in.invites_keycloak.exception.ActiveInviteExistsException
import com.github.hu553in.invites_keycloak.service.InviteService
import com.github.hu553in.invites_keycloak.service.MailService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.mock
import org.mockito.BDDMockito.reset
import org.mockito.BDDMockito.then
import org.mockito.BDDMockito.verifyNoInteractions
import org.mockito.BDDMockito.verifyNoMoreInteractions
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.*

@WebMvcTest(InviteAdminController::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(
    InviteAdminControllerTest.TestConfig::class,
    TestClientRegistrationRepositoryConfig::class
)
@EnableConfigurationProperties(InviteProps::class)
@TestPropertySource(
    properties = [
        "invite.public-base-url=https://app.example.com",
        "invite.expiry.default=PT24H",
        "invite.expiry.min=PT5M",
        "invite.expiry.max=P30D",
        "invite.cleanup.retention=P30D",
        "invite.realms.master.roles[0]=default-admin",
        "invite.realms.other.roles[0]=auditor",
        "invite.realms.no-roles.roles=",
        "invite.token.secret=01234567890123456789012345678901",
        "invite.token.bytes=32",
        "invite.token.salt-bytes=16",
        "invite.token.mac-algorithm=HmacSHA256",
        "keycloak.url=https://id.example.com",
        "keycloak.realm=master",
        "keycloak.client-id=test-client",
        "keycloak.client-secret=test-secret",
        "keycloak.required-role=invite-admin",
        "keycloak.max-attempts=1",
        "keycloak.min-backoff=PT1S"
    ]
)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class InviteAdminControllerTest(
    private val mockMvc: MockMvc,
    private val inviteProps: InviteProps,
    private val clock: Clock,
    private val inviteService: InviteService,
    private val mailService: MailService,
    private val keycloakAdminClient: KeycloakAdminClient
) {

    @BeforeEach
    fun resetMocks() {
        reset(inviteService, mailService, keycloakAdminClient)
    }

    @TestConfiguration
    class TestConfig {
        @Bean
        fun clock(): Clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC)

        @Bean
        @Primary
        fun inviteService(): InviteService = mock(InviteService::class.java)

        @Bean
        @Primary
        fun mailService(): MailService = mock(MailService::class.java)

        @Bean
        @Primary
        fun keycloakAdminClient(): KeycloakAdminClient = mock(KeycloakAdminClient::class.java)
    }

    @Test
    fun `list view renders invites`() {
        // arrange
        val invite = sampleInviteEntity()
        given(inviteService.listInvites()).willReturn(listOf(invite))

        // act
        val result = mockMvc.get("/admin/invite")

        // assert
        result.andExpect {
            status { isOk() }
            view { name("admin/invite/list") }
            model { attributeExists("invites") }
        }
        then(inviteService).should().listInvites()
    }

    @Test
    fun `new invite form uses default realm`() {
        // arrange
        given(keycloakAdminClient.listRealmRoles("master")).willReturn(listOf("default-admin"))

        // act
        val result = mockMvc.get("/admin/invite/new")

        // assert
        result.andExpect {
            status { isOk() }
            view { name("admin/invite/new") }
            model {
                attribute("selectedRealm", "master")
                attribute("rolesVisible", true)
                attribute("rolesAvailable", true)
                attributeExists("inviteForm")
            }
        }
        then(keycloakAdminClient).should().listRealmRoles("master")
    }

    @Test
    fun `new invite form switches realms`() {
        // arrange
        given(keycloakAdminClient.listRealmRoles("other")).willReturn(listOf("auditor", "reviewer"))

        // act
        mockMvc.get("/admin/invite/new") {
            param("realm", "other")
        }.andExpect {
            status { isOk() }
            model {
                attribute("selectedRealm", "other")
                attribute("rolesVisible", true)
                attribute("rolesAvailable", true)
            }
        }

        then(keycloakAdminClient).should().listRealmRoles("other")
    }

    @Test
    fun `new invite form hides roles when realm has none configured`() {
        // act
        val result = mockMvc.get("/admin/invite/new") {
            param("realm", "no-roles")
        }

        // assert
        result.andExpect {
            status { isOk() }
            view { name("admin/invite/new") }
            model {
                attribute("selectedRealm", "no-roles")
                attribute("rolesVisible", false)
                attribute("rolesAvailable", true)
            }
        }
        verifyNoInteractions(keycloakAdminClient)
    }

    @Test
    fun `new invite form blocks when roles unavailable`() {
        // arrange
        given(keycloakAdminClient.listRealmRoles("master")).willThrow(RuntimeException("boom"))

        // act
        val result = mockMvc.get("/admin/invite/new")

        // assert
        result.andExpect {
            status { isOk() }
            view { name("admin/invite/new") }
            model {
                attribute("rolesAvailable", false)
                attribute("rolesVisible", true)
                attributeExists("rolesFetchError")
            }
        }
    }

    @Test
    fun `create invite success sends mail and redirects`() {
        // arrange
        val expectedExpiry = clock.instant().plus(Duration.ofMinutes(1440))
        val createdInvite = InviteService.CreatedInvite(sampleInviteEntity(), "raw.token")
        val expectedMailData = MailService.InviteMailData(
            email = createdInvite.invite.email,
            target = createdInvite.invite.realm,
            link = "https://app.example.com/invite/${createdInvite.invite.realm}/${createdInvite.rawToken}",
            expiresAt = createdInvite.invite.expiresAt
        )
        given(
            inviteService.createInvite(
                realm = "master",
                email = "admin@example.com",
                expiresAt = expectedExpiry,
                maxUses = 1,
                roles = setOf("default-admin"),
                createdBy = "system"
            )
        ).willReturn(createdInvite)
        given(mailService.sendInviteEmail(expectedMailData)).willReturn(MailService.MailSendStatus.OK)
        given(keycloakAdminClient.listRealmRoles("master")).willReturn(listOf("default-admin"))

        // act
        val result = mockMvc.post("/admin/invite") {
            param("realm", "master")
            param("email", "admin@example.com")
            param("expiryMinutes", "1440")
            param("maxUses", "1")
            param("roles", "default-admin")
        }

        // assert
        result.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/admin/invite")
            flash {
                attribute("successMessage", "Invite created for admin@example.com")
                attributeExists("inviteLink")
                attribute("mailStatusLevel", "info")
            }
        }

        then(mailService).should().sendInviteEmail(expectedMailData)
    }

    @Test
    fun `create invite without roles when realm has none`() {
        // arrange
        val expectedExpiry = clock.instant().plus(Duration.ofMinutes(1440))
        val createdInvite = InviteService.CreatedInvite(
            sampleInviteEntity(
                realm = "no-roles",
                email = "no-roles@example.com",
                roles = emptySet()
            ),
            "raw.token.no-roles"
        )
        val expectedMailData = MailService.InviteMailData(
            email = createdInvite.invite.email,
            target = createdInvite.invite.realm,
            link = "https://app.example.com/invite/${createdInvite.invite.realm}/${createdInvite.rawToken}",
            expiresAt = createdInvite.invite.expiresAt
        )
        given(
            inviteService.createInvite(
                realm = "no-roles",
                email = "no-roles@example.com",
                expiresAt = expectedExpiry,
                maxUses = 1,
                roles = emptySet(),
                createdBy = "system"
            )
        ).willReturn(createdInvite)
        given(mailService.sendInviteEmail(expectedMailData)).willReturn(MailService.MailSendStatus.OK)

        // act
        val result = mockMvc.post("/admin/invite") {
            param("realm", "no-roles")
            param("email", "no-roles@example.com")
            param("expiryMinutes", "1440")
            param("maxUses", "1")
        }

        // assert
        result.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/admin/invite")
            flash {
                attribute("successMessage", "Invite created for no-roles@example.com")
                attributeExists("inviteLink")
                attribute("mailStatusLevel", "info")
            }
        }

        verifyNoInteractions(keycloakAdminClient)
        then(mailService).should().sendInviteEmail(expectedMailData)
    }

    @Test
    fun `create invite duplicate email shows field error`() {
        // arrange
        given(keycloakAdminClient.listRealmRoles("master")).willReturn(listOf("default-admin"))
        BDDMockito.`when`(
            inviteService.createInvite(
                BDDMockito.anyString(),
                BDDMockito.anyString(),
                BDDMockito.any(Instant::class.java),
                BDDMockito.anyInt(),
                BDDMockito.anySet(),
                BDDMockito.anyString()
            )
        ).thenThrow(ActiveInviteExistsException("master", "admin@example.com"))

        // act
        val result = mockMvc.post("/admin/invite") {
            param("realm", "master")
            param("email", "admin@example.com")
            param("expiryMinutes", "1440")
            param("maxUses", "1")
        }

        // assert
        result.andExpect {
            status { isOk() }
            view { name("admin/invite/new") }
            model {
                attributeHasFieldErrors("inviteForm", "email")
            }
        }
        verifyNoInteractions(mailService)
    }

    @Test
    fun `create invite with invalid expiry shows field error`() {
        // arrange
        given(keycloakAdminClient.listRealmRoles("master")).willReturn(listOf("default-admin"))

        // act
        val result = mockMvc.post("/admin/invite") {
            param("realm", "master")
            param("email", "admin@example.com")
            param("expiryMinutes", inviteProps.expiry.min.minusMinutes(1).toMinutes().toString())
            param("maxUses", "1")
            param("roles", "default-admin")
        }

        // assert
        result.andExpect {
            status { isOk() }
            view { name("admin/invite/new") }
            model { attributeHasFieldErrors("inviteForm", "expiryMinutes") }
        }
        verifyNoInteractions(inviteService)
    }

    @Test
    fun `create invite rejects roles outside allowed list`() {
        // arrange
        given(keycloakAdminClient.listRealmRoles("master")).willReturn(listOf("allowed-role"))

        // act
        val result = mockMvc.post("/admin/invite") {
            param("realm", "master")
            param("email", "admin@example.com")
            param("expiryMinutes", "1440")
            param("maxUses", "1")
            param("roles", "default-admin")
        }

        // assert
        result.andExpect {
            status { isOk() }
            view { name("admin/invite/new") }
            model { attributeHasFieldErrors("inviteForm", "roles") }
        }
        verifyNoInteractions(inviteService)
    }

    @Test
    fun `create invite fails when roles cannot be fetched`() {
        // arrange
        given(keycloakAdminClient.listRealmRoles("master")).willThrow(RuntimeException("boom"))

        // act
        val result = mockMvc.post("/admin/invite") {
            param("realm", "master")
            param("email", "admin@example.com")
            param("expiryMinutes", "1440")
            param("maxUses", "1")
            param("roles", "default-admin")
        }

        // assert
        result.andExpect {
            status { isOk() }
            view { name("admin/invite/new") }
            model {
                attributeHasErrors("inviteForm")
                attribute("rolesAvailable", false)
            }
        }
        verifyNoInteractions(inviteService)
    }

    @Test
    fun `resend invite success shows link`() {
        // arrange
        val inviteId = UUID.randomUUID()
        val existing = sampleInviteEntity(id = inviteId, realm = "other", email = "user@example.com")
        val created = InviteService.CreatedInvite(
            sampleInviteEntity(realm = "other", email = "user@example.com"),
            "token.resend"
        )
        val expectedMailData = MailService.InviteMailData(
            email = created.invite.email,
            target = created.invite.realm,
            link = "https://app.example.com/invite/${created.invite.realm}/${created.rawToken}",
            expiresAt = created.invite.expiresAt
        )
        val expectedExpiry = clock.instant().plus(Duration.ofMinutes(1440))
        given(inviteService.get(inviteId)).willReturn(existing)
        given(mailService.sendInviteEmail(expectedMailData)).willReturn(MailService.MailSendStatus.NOT_CONFIGURED)
        given(inviteService.resendInvite(inviteId, expectedExpiry, "system")).willReturn(created)
        given(keycloakAdminClient.listRealmRoles("other")).willReturn(existing.roles.toList())

        // act
        val result = mockMvc.post("/admin/invite/$inviteId/resend") {
            param("expiryMinutes", "1440")
        }

        // assert
        result.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/admin/invite")
            flash {
                attribute("inviteLink", "https://app.example.com/invite/other/token.resend")
                attribute("mailStatusLevel", "warning")
            }
        }
        then(inviteService).should().resendInvite(inviteId, expectedExpiry, "system")
        then(mailService).should().sendInviteEmail(expectedMailData)
    }

    @Test
    fun `resend invite allows revoked invite`() {
        // arrange
        val inviteId = UUID.randomUUID()
        val existing = sampleInviteEntity(id = inviteId, realm = "other", email = "user@example.com").apply {
            revoked = true
        }
        val created = InviteService.CreatedInvite(
            sampleInviteEntity(realm = "other", email = "user@example.com"),
            "token.resend.revoked"
        )
        val expectedMailData = MailService.InviteMailData(
            email = created.invite.email,
            target = created.invite.realm,
            link = "https://app.example.com/invite/${created.invite.realm}/${created.rawToken}",
            expiresAt = created.invite.expiresAt
        )
        val expectedExpiry = clock.instant().plus(Duration.ofMinutes(1440))
        given(inviteService.get(inviteId)).willReturn(existing)
        given(keycloakAdminClient.listRealmRoles("other")).willReturn(existing.roles.toList())
        given(inviteService.resendInvite(inviteId, expectedExpiry, "system")).willReturn(created)
        given(mailService.sendInviteEmail(expectedMailData)).willReturn(MailService.MailSendStatus.OK)

        // act
        val result = mockMvc.post("/admin/invite/$inviteId/resend") {
            param("expiryMinutes", "1440")
        }

        // assert
        result.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/admin/invite")
            flash {
                attribute("inviteLink", "https://app.example.com/invite/other/token.resend.revoked")
                attribute("mailStatusLevel", "info")
            }
        }
        then(inviteService).should().resendInvite(inviteId, expectedExpiry, "system")
        then(mailService).should().sendInviteEmail(expectedMailData)
    }

    @Test
    fun `resend invite blocks when roles missing in Keycloak`() {
        // arrange
        val inviteId = UUID.randomUUID()
        val existing = sampleInviteEntity(id = inviteId, realm = "master", email = "user@example.com")
        given(inviteService.get(inviteId)).willReturn(existing)
        given(keycloakAdminClient.listRealmRoles("master")).willReturn(listOf("other-role"))

        // act
        val result = mockMvc.post("/admin/invite/$inviteId/resend") {
            param("expiryMinutes", "1440")
        }

        // assert
        result.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/admin/invite")
            flash { attributeExists("errorMessage") }
        }
        verifyNoInteractions(mailService)
        then(inviteService).should().get(inviteId)
        verifyNoMoreInteractions(inviteService)
    }

    @Test
    fun `resend invite rejects invalid expiry`() {
        // arrange
        val inviteId = UUID.randomUUID()

        // act
        val result = mockMvc.post("/admin/invite/$inviteId/resend") {
            param("expiryMinutes", inviteProps.expiry.min.minusMinutes(1).toMinutes().toString())
        }

        // assert
        result.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/admin/invite")
            flash { attributeExists("errorMessage") }
        }
        verifyNoInteractions(inviteService)
    }

    @Test
    fun `delete invite success redirects with message`() {
        // arrange
        val inviteId = UUID.randomUUID()
        val invite = sampleInviteEntity(id = inviteId, email = "user@example.com").apply { revoked = true }
        given(inviteService.delete(inviteId)).willReturn(invite)

        // act
        val result = mockMvc.post("/admin/invite/$inviteId/delete")

        // assert
        result.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/admin/invite")
            flash { attribute("successMessage", "Invite deleted for user@example.com") }
        }
        then(inviteService).should().delete(inviteId)
    }

    @Test
    fun `delete invite shows error when active`() {
        // arrange
        val inviteId = UUID.randomUUID()
        given(inviteService.delete(inviteId)).willThrow(
            IllegalStateException("Invite $inviteId is active; revoke it before deleting.")
        )

        // act
        val result = mockMvc.post("/admin/invite/$inviteId/delete")

        // assert
        result.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/admin/invite")
            flash { attributeExists("errorMessage") }
        }
        then(inviteService).should().delete(inviteId)
    }

    private fun sampleInviteEntity(
        id: UUID = UUID.randomUUID(),
        realm: String = "master",
        email: String = "admin@example.com",
        roles: Set<String> = setOf("default-admin")
    ): InviteEntity {
        return InviteEntity(
            id = id,
            realm = realm,
            tokenHash = "hash",
            salt = "salt",
            email = email,
            createdBy = "creator",
            createdAt = clock.instant(),
            expiresAt = clock.instant().plus(inviteProps.expiry.default),
            maxUses = 1,
            roles = roles
        )
    }
}
