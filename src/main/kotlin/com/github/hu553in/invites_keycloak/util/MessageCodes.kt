package com.github.hu553in.invites_keycloak.util

object MessageCodes {
    object AdminError {
        const val CREATE_INVITE_INPUT_INVALID = "admin.invite.create.input_invalid"
        const val CREATE_INVITE_SERVER_ERROR = "admin.invite.create.server_error"
        const val REVOKE_INVITE_FAILED = "admin.invite.revoke.failed"
        const val DELETE_INVITE_FAILED = "admin.invite.delete.failed"
        const val RESEND_INVITE_FAILED = "admin.invite.resend.failed"
        const val RESEND_ROLES_UNAVAILABLE = "admin.invite.resend.roles_unavailable"
        const val RESEND_ROLES_MISSING = "admin.invite.resend.roles_missing"
        const val ROLES_UNAVAILABLE_FOR_FORM = "admin.roles.unavailable.form"
        const val MAIL_NOT_SENT_SMTP_NOT_CONFIGURED = "admin.mail.not_sent.smtp_not_configured"
        const val MAIL_NOT_SENT_CHECK_LOGS = "admin.mail.not_sent.check_logs"
        const val EXPIRY_RANGE_INVALID = "admin.expiry.range_invalid"
    }

    object Error {
        const val SERVICE_TEMP_UNAVAILABLE = "error.service.temp_unavailable"
        const val SERVICE_TEMP_UNAVAILABLE_DETAILS = "error.service.temp_unavailable.details"
        const val INVITE_CANNOT_BE_REDEEMED = "error.invite.cannot_be_redeemed"
        const val INVITE_CANNOT_BE_REDEEMED_DETAILS = "error.invite.cannot_be_redeemed.details"
        const val INVITE_NO_LONGER_VALID = "error.invite.no_longer_valid"
        const val INVITE_NO_LONGER_VALID_DETAILS = "error.invite.no_longer_valid.details"
        const val ACCOUNT_ALREADY_EXISTS = "error.account.already_exists"
        const val ACCOUNT_ALREADY_EXISTS_DETAILS = "error.account.already_exists.details"
        const val INVITE_NOT_FOUND = "error.invite.not_found"
        const val INVITE_NOT_FOUND_DETAILS = "error.invite.not_found.details"
        const val INVITE_INVALID = "error.invite.invalid"
        const val INVITE_INVALID_DETAILS = "error.invite.invalid.details"
        const val INVITE_CONFIRMATION_INVALID = "error.invite.confirmation.invalid"
        const val INVITE_CONFIRMATION_INVALID_DETAILS = "error.invite.confirmation.invalid.details"
    }

    object Success {
        const val ADMIN_INVITE_CREATED = "success.admin.invite.created"
        const val ADMIN_INVITE_REVOKED = "success.admin.invite.revoked"
        const val ADMIN_INVITE_DELETED = "success.admin.invite.deleted"
        const val ADMIN_INVITE_RESENT = "success.admin.invite.resent"
        const val ADMIN_INVITE_EMAIL_SENT = "success.admin.invite.email_sent"
    }

    object Mail {
        const val DEFAULT_INVITE_SUBJECT = "mail.invite.subject"
    }

    object InviteStatus {
        const val ACTIVE = "invite.status.active"
        const val OVERUSED = "invite.status.overused"
        const val EXPIRED = "invite.status.expired"
        const val REVOKED = "invite.status.revoked"
    }

    object ErrorCode {
        const val ADMIN_EMAIL_DUPLICATE = "email.duplicate"
        const val ADMIN_INVITE_CREATE_FAILED = "invite.create.failed"
        const val ADMIN_ROLES_UNAVAILABLE = "roles.unavailable"
        const val ADMIN_ROLES_INVALID = "roles.invalid"
        const val ADMIN_REALM_EMPTY = "realm.empty"
        const val ADMIN_REALM_INVALID = "realm.invalid"
        const val ADMIN_EXPIRY_INVALID = "expiry.invalid"
    }
}
