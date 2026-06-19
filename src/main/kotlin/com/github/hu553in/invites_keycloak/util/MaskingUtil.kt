package com.github.hu553in.invites_keycloak.util

/**
 * Masks sensitive values by keeping only the first and last characters visible
 * and replacing the middle part with three asterisks. Example: "alice@example.com" -> "a***m".
 *
 * If the input is blank, returns "***".
 */
private const val MIN_MASK_LENGTH = 3

fun maskSensitive(value: String?): String {
    val normalized = value?.trim().orEmpty()
    if (normalized.length < MIN_MASK_LENGTH) {
        return "***"
    }

    val first = normalized.first()
    val last = normalized.last()
    return "$first***$last"
}
