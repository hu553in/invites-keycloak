package com.github.hu553in.invites_keycloak.util

object MailMessages {
    const val DEFAULT_INVITE_SUBJECT_TEMPLATE = "Invitation to %s"

    fun defaultInviteSubject(realm: String): String {
        return DEFAULT_INVITE_SUBJECT_TEMPLATE.format(realm)
    }
}
