package com.github.hu553in.invites_keycloak.util

import java.util.*

fun normalizeString(string: String, message: String, lowercase: Boolean = false): String {
    var normalized = string.trim()
    if (lowercase) {
        normalized = normalized.lowercase(Locale.ROOT)
    }
    require(normalized.isNotBlank()) { message }
    return normalized
}

fun normalizeStrings(strings: Set<String>, message: String, default: Set<String> = emptySet()): Set<String> {
    var normalized = strings
        .map { it.trim() }
        .filterTo(mutableSetOf()) { it.isNotBlank() }
    if (normalized.isEmpty()) {
        normalized = default
            .map { it.trim() }
            .filterTo(mutableSetOf()) { it.isNotBlank() }
    }
    require(normalized.isNotEmpty()) { message }
    return normalized
}
