package com.github.hu553in.invites_keycloak.util

object ErrorMessages {
    const val SERVICE_TEMP_UNAVAILABLE = "Service temporarily unavailable. Please retry later."

    const val SERVICE_TEMP_UNAVAILABLE_DETAILS =
        "Keycloak is currently unavailable. Your invite remains valid; try again in a few minutes."

    const val INVITE_NO_LONGER_VALID = "Invite is no longer valid. Please request a new invite."

    const val INVITE_NO_LONGER_VALID_DETAILS =
        "The invitation could not be completed because required Keycloak data is missing or invalid. " +
            "Ask your administrator to issue a new invite."
}
