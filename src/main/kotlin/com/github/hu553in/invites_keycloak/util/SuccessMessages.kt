package com.github.hu553in.invites_keycloak.util

object SuccessMessages {
    fun adminInviteCreated(email: String): String = "Invite created for $email"

    fun adminInviteRevoked(email: String): String = "Invite revoked for $email"

    fun adminInviteDeleted(email: String): String = "Invite deleted for $email"

    fun adminInviteResent(email: String): String = "Invite resent to $email"

    fun adminInviteEmailSent(email: String): String = "Invite email sent to $email"
}
