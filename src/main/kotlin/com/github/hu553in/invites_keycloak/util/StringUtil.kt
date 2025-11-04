package com.github.hu553in.invites_keycloak.util

import java.util.*

fun normalizeString(
    string: String,
    message: String = "must not be empty",
    lowercase: Boolean = false,
    required: Boolean = true
): String {
    var normalized = string.trim()
    if (lowercase) {
        normalized = normalized.lowercase(Locale.ROOT)
    }
    if (required) {
        require(normalized.isNotBlank()) { message }
    }
    return normalized
}

fun normalizeStrings(
    strings: Set<String>,
    message: String = "must not be empty",
    default: Set<String> = emptySet(),
    required: Boolean = true
): Set<String> {
    var normalized = strings
        .map { it.trim() }
        .filterTo(mutableSetOf()) { it.isNotBlank() }
    if (normalized.isEmpty()) {
        normalized = default
            .map { it.trim() }
            .filterTo(mutableSetOf()) { it.isNotBlank() }
    }
    if (required) {
        require(normalized.isNotEmpty()) { message }
    }
    return normalized
}
