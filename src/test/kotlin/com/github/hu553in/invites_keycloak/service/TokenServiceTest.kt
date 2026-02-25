package com.github.hu553in.invites_keycloak.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.hu553in.invites_keycloak.config.props.InviteProps
import com.github.hu553in.invites_keycloak.util.ARG_EXPECTED_BYTES_KEY
import com.github.hu553in.invites_keycloak.util.ARG_KEY
import com.github.hu553in.invites_keycloak.util.ARG_VALUE_LENGTH_KEY
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.mockito.BDDMockito.mock
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.test.Test

class TokenServiceTest {

    private val inviteProps = InviteProps(
        publicBaseUrl = "https://app.example.com",
        expiry = mock(),
        realms = mock(),
        token = InviteProps.TokenProps(
            secret = "test-secret-value",
            bytes = 32,
            saltBytes = 16,
            macAlgorithm = "HmacSHA256"
        ),
        cleanup = mock()
    )

    private val tokenService = TokenService(inviteProps)
    private val base64Decoder = Base64.getUrlDecoder()

    @Test
    fun `different salts produce different hashes`() {
        // arrange
        val token = tokenService.generateToken()
        val saltOne = tokenService.generateSalt()
        val saltTwo = tokenService.generateSalt()

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
        val token = tokenService.generateToken()
        val salt = tokenService.generateSalt()

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
        val token = tokenService.generateToken()
        val salt = tokenService.generateSalt()
        val h1 = tokenService.hashToken(token, salt)
        val h2 = tokenService.hashToken(token, salt)

        // act
        val ok = TokenService.hashesEqualConstantTime(h1, h2)

        // assert
        assertThat(ok).isTrue()
    }

    @Test
    fun `hashToken logs malformed argument diagnostics at debug level`() {
        // arrange
        val validSalt = tokenService.generateSalt()
        val logger = LoggerFactory.getLogger(TokenService::class.java) as Logger
        val previousLevel = logger.level
        logger.level = Level.DEBUG
        val listAppender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(listAppender)

        try {
            // act
            assertThatThrownBy { tokenService.hashToken("", validSalt) }
                // assert
                .isInstanceOf(IllegalArgumentException::class.java)

            val event = listAppender.list.first { it.formattedMessage == "Token hashing argument validation failed" }
            val keyValues = (event.keyValuePairs ?: emptyList())
                .associate { it.key to (it.value?.toString() ?: "null") }

            assertThat(event.level).isEqualTo(Level.DEBUG)
            assertThat(keyValues).containsEntry(ARG_KEY, "token")
            assertThat(keyValues).containsEntry(ARG_EXPECTED_BYTES_KEY, inviteProps.token.bytes.toString())
            assertThat(keyValues).containsEntry(ARG_VALUE_LENGTH_KEY, "0")
        } finally {
            logger.detachAppender(listAppender)
            logger.level = previousLevel
        }
    }

    private fun verifyAlphabet(s: String) {
        assertThat(s).doesNotContain("=")
        assertThat(s).matches("^[A-Za-z0-9_-]+$")
    }
}
