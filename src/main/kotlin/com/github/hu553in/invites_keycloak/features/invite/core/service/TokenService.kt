package com.github.hu553in.invites_keycloak.features.invite.core.service

import com.github.hu553in.invites_keycloak.features.invite.config.InviteProps
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

    init {
        require(inviteProps.token.secret.toByteArray(Charsets.UTF_8).size >= MIN_TOKEN_SECRET_BYTES) {
            "invite.token.secret size must be greater than or equal to $MIN_TOKEN_SECRET_BYTES bytes"
        }

        try {
            Mac.getInstance(inviteProps.token.macAlgorithm)
        } catch (e: NoSuchAlgorithmException) {
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

    fun generateToken(): String = generateRandomString(inviteProps.token.bytes)

    fun generateSalt(): String = generateRandomString(inviteProps.token.saltBytes)

    fun hashToken(token: String, salt: String): String {
        validateHashTokenArg(token, "token", inviteProps.token.bytes)
        validateHashTokenArg(salt, "salt", inviteProps.token.saltBytes)

        val bytes = mac().doFinal(payload(token, salt))
        return hexFormat.formatHex(bytes)
    }

    private fun validateHashTokenArg(arg: String, argName: String, bytes: Int) {
        require(
            arg.isNotBlank() &&
                base64Decoder.decode(arg).size == bytes &&
                !arg.contains("=") &&
                arg.matches(alphabetRegex)
        ) { "$argName is invalid" }
    }

    private fun generateRandomString(size: Int): String {
        val bytes = ByteArray(size).apply(random::nextBytes)
        return base64Encoder.encodeToString(bytes)
    }

    private fun mac(): Mac = Mac.getInstance(inviteProps.token.macAlgorithm)
        .apply { init(secretKeySpec) }

    private fun payload(token: String, salt: String) = "$token:$salt".toByteArray(Charsets.UTF_8)
}
