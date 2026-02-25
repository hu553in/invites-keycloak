package com.github.hu553in.invites_keycloak.util

object ErrorMessages {
    const val SERVICE_TEMP_UNAVAILABLE = "Service is temporarily unavailable. Please retry later."

    const val SERVICE_TEMP_UNAVAILABLE_DETAILS =
        "Keycloak is currently unavailable. Your invite remains valid; try again in a few minutes."

    const val INVITE_CANNOT_BE_REDEEMED = "Invite cannot be redeemed right now. Please contact an administrator."

    const val INVITE_CANNOT_BE_REDEEMED_DETAILS =
        "Keycloak rejected the invite request. Ask an administrator to review the invite and try again."

    const val INVITE_NO_LONGER_VALID = "Invite is no longer valid. Please request a new invite."

    const val INVITE_NO_LONGER_VALID_DETAILS = "The invitation could not be redeemed because required Keycloak data " +
        "is missing or invalid. Ask your administrator to issue a new invite."

    const val ACCOUNT_ALREADY_EXISTS = "Account already exists."

    const val ACCOUNT_ALREADY_EXISTS_DETAILS = "An account with passed data is already registered. " +
        "If you believe this is an error, please contact your administrator."

    const val INVITE_NOT_FOUND = "Invite is not found."

    const val INVITE_NOT_FOUND_DETAILS = "Please check that you opened the full invite link or request a new invite."

    const val INVITE_INVALID = "Invite is not valid."

    const val INVITE_INVALID_DETAILS = "Please reopen the invite link or request a new invite."

    const val INVITE_CONFIRMATION_INVALID = "Invite confirmation is no longer valid."

    const val INVITE_CONFIRMATION_INVALID_DETAILS = "Please reopen the invite link and try again."
}
