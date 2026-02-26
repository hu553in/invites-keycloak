package com.github.hu553in.invites_keycloak.util

object AdminErrorMessages {
    const val CREATE_INVITE_INPUT_INVALID = "Unable to create invite; please check input and try again."
    const val CREATE_INVITE_SERVER_ERROR = "Unable to create invite due to server error. Please retry later."

    const val REALM_REQUIRED = "Realm is required."
    const val REALM_NOT_ALLOWED = "Realm is not allowed."

    const val ROLES_UNAVAILABLE_FOR_VALIDATION =
        "Unable to fetch realm roles from Keycloak right now. Please try again."

    const val ROLES_INVALID = "Selected roles are not available in Keycloak."

    const val REVOKE_INVITE_FAILED = "Failed to revoke invite."
    const val DELETE_INVITE_FAILED = "Failed to delete invite."
    const val RESEND_INVITE_FAILED = "Failed to resend invite."

    const val RESEND_ROLES_UNAVAILABLE = "Cannot resend invite now: roles are unavailable (Keycloak may be down)."

    const val RESEND_ROLES_MISSING =
        "Cannot resend invite: roles no longer exist in Keycloak. Create a new invite with valid roles."

    const val ROLES_UNAVAILABLE_FOR_FORM =
        "Roles are temporarily unavailable. Keycloak may be down; please retry later."

    const val MAIL_NOT_SENT_SMTP_NOT_CONFIGURED = "Invite email not sent: SMTP is not configured."
    const val MAIL_NOT_SENT_CHECK_LOGS = "Invite email could not be sent. Please check the mail logs."

    fun activeInviteAlreadyExists(email: String, realm: String): String =
        "Active invite already exists for ${maskSensitive(email)} in realm $realm."

    fun expiryRangeInvalid(minMinutes: Long, maxMinutes: Long): String =
        "Expiry must be between $minMinutes and $maxMinutes minutes."
}
