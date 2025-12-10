package com.github.hu553in.invites_keycloak.service

import com.github.hu553in.invites_keycloak.config.props.InviteProps
import com.github.hu553in.invites_keycloak.util.logger
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
class TokenService(
    private val inviteProps: InviteProps
) {

    private val log by logger()

    init {
        val secretBytes = inviteProps.token.secret.toByteArray(Charsets.UTF_8).size
        if (secretBytes < MIN_TOKEN_SECRET_BYTES) {
            log.atError()
                .addKeyValue("configured_bytes") { secretBytes }
                .addKeyValue("required_min_bytes") { MIN_TOKEN_SECRET_BYTES }
                .log { "Invite token secret is too short; failing startup" }
            throw IllegalArgumentException(
                "invite.token.secret size must be greater than or equal to $MIN_TOKEN_SECRET_BYTES bytes"
            )
        }

        try {
            Mac.getInstance(inviteProps.token.macAlgorithm)
        } catch (e: NoSuchAlgorithmException) {
            log.atError()
                .addKeyValue("mac_algorithm") { inviteProps.token.macAlgorithm }
                .setCause(e)
                .log { "Invite token MAC algorithm is invalid; failing startup" }
            throw IllegalArgumentException("invite.token.mac-algorithm is invalid", e)
        }
    }

    companion object {
        private const val MIN_TOKEN_SECRET_BYTES = 16

        private val alphabetRegex = Regex("^[A-Za-z0-9_-]+$")

        private val random = SecureRandom()
        private val base64Encoder = Base64.getUrlEncoder().withoutPadding()
        private val base64Decoder = Base64.getUrlDecoder()
        private val hexFormat = HexFormat.of()

        @JvmStatic
        fun hashesEqualConstantTime(a: String, b: String): Boolean {
            val h1 = hexFormat.parseHex(a)
            val h2 = hexFormat.parseHex(b)
            return MessageDigest.isEqual(h1, h2)
        }
    }

    private val secretKeySpec = SecretKeySpec(
        inviteProps.token.secret.toByteArray(Charsets.UTF_8),
        inviteProps.token.macAlgorithm
    )

    fun generateToken(): String {
        val token = generateRandomString(inviteProps.token.bytes)
        log.atDebug()
            .addKeyValue("token_bytes") { inviteProps.token.bytes }
            .log { "Generated invite token" }
        return token
    }

    fun generateSalt(): String {
        val salt = generateRandomString(inviteProps.token.saltBytes)
        log.atDebug()
            .addKeyValue("salt_bytes") { inviteProps.token.saltBytes }
            .log { "Generated invite salt" }
        return salt
    }

    fun hashToken(token: String, salt: String): String {
        validateHashTokenArg(token, "token", inviteProps.token.bytes)
        validateHashTokenArg(salt, "salt", inviteProps.token.saltBytes)

        val bytes = mac().doFinal(payload(token, salt))
        val tokenHash = hexFormat.formatHex(bytes)
        log.atDebug()
            .addKeyValue("hash_length") { tokenHash.length }
            .log { "Hashed invite token" }
        return tokenHash
    }

    private fun validateHashTokenArg(arg: String, argName: String, bytes: Int) {
        try {
            require(
                arg.isNotBlank() &&
                    base64Decoder.decode(arg).size == bytes &&
                    !arg.contains("=") &&
                    arg.matches(alphabetRegex)
            ) { "$argName is invalid" }
        } catch (e: IllegalArgumentException) {
            log.atDebug()
                .addKeyValue("arg") { argName }
                .log { "Token hashing argument validation failed" }
            throw e
        }
    }

    private fun generateRandomString(size: Int): String {
        val bytes = ByteArray(size).apply(random::nextBytes)
        return base64Encoder.encodeToString(bytes)
    }

    private fun mac(): Mac = Mac.getInstance(inviteProps.token.macAlgorithm)
        .apply { init(secretKeySpec) }

    private fun payload(token: String, salt: String) = "$token:$salt".toByteArray(Charsets.UTF_8)
}
