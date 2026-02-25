package com.github.hu553in.invites_keycloak.util

object SuccessMessages {
    fun adminInviteCreated(email: String): String {
        return "Invite created for $email"
    }

    fun adminInviteRevoked(email: String): String {
        return "Invite revoked for $email"
    }

    fun adminInviteDeleted(email: String): String {
        return "Invite deleted for $email"
    }

    fun adminInviteResent(email: String): String {
        return "Invite resent to $email"
    }

    fun adminInviteEmailSent(email: String): String {
        return "Invite email sent to $email"
    }
}
