package com.github.hu553in.invites_keycloak.features.invite.core.service

import com.github.hu553in.invites_keycloak.features.invite.config.InviteProps
import org.assertj.core.api.Assertions.assertThat
import org.mockito.Mockito.mock
import java.util.*
import kotlin.test.Test

class TokenServiceTest {

    private val inviteProps = InviteProps(
        expiry = mock(),
        realms = mock(),
        token = InviteProps.TokenProps(
            secret = "test-secret-value",
            bytes = 32,
            saltBytes = 16,
            macAlgorithm = "HmacSHA256"
        )
    )

    private val tokenService = TokenService(inviteProps)
    private val base64Decoder = Base64.getUrlDecoder()

    @Test
    fun `different salts produce different hashes`() {
        // arrange
        val token = "integration-token"
        val saltOne = "first-salt"
        val saltTwo = "second-salt"

        // act
        val hashOne = tokenService.hashToken(token, saltOne)
        val hashTwo = tokenService.hashToken(token, saltTwo)

        // assert
        assertThat(hashOne).isNotBlank()
        assertThat(hashTwo).isNotBlank()
        assertThat(hashOne).isNotEqualTo(hashTwo)
    }

    @Test
    fun `hash is deterministic for identical input`() {
        // arrange
        val token = "integration-token"
        val salt = "repeatable-salt"

        // act
        val hashOne = tokenService.hashToken(token, salt)
        val hashTwo = tokenService.hashToken(token, salt)

        // assert
        assertThat(hashOne).isNotBlank()
        assertThat(hashOne).isEqualTo(hashTwo)
    }

    @Test
    fun `generateToken generates correct length and alphabet`() {
        // act
        val generated = tokenService.generateToken()

        // assert
        assertThat(generated).isNotBlank()
        assertThat(base64Decoder.decode(generated).size).isEqualTo(inviteProps.token.bytes)
        verifyAlphabet(generated)
    }

    @Test
    fun `generateSalt generates correct length and alphabet`() {
        // act
        val generated = tokenService.generateSalt()

        // assert
        assertThat(generated).isNotBlank()
        assertThat(base64Decoder.decode(generated).size).isEqualTo(inviteProps.token.saltBytes)
        verifyAlphabet(generated)
    }

    @Test
    fun `token is URL-safe and unpadded`() {
        // act
        val generated = tokenService.generateToken()

        // assert
        verifyAlphabet(generated)
    }

    @Test
    fun `constant time equals matches normal equality for identical hashes`() {
        // arrange
        val h1 = tokenService.hashToken("t", "s")
        val h2 = tokenService.hashToken("t", "s")

        // act
        val ok = TokenService.hashesEqualConstantTime(h1, h2)

        // assert
        assertThat(ok).isTrue()
    }

    private fun verifyAlphabet(s: String) {
        assertThat(s).doesNotContain("=")
        assertThat(s).matches("^[A-Za-z0-9_-]+$")
    }
}
