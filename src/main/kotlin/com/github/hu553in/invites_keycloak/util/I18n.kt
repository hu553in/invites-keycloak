package com.github.hu553in.invites_keycloak.util

import org.springframework.context.MessageSource
import java.util.*

fun MessageSource.msg(code: String, locale: Locale, vararg args: Any): String = getMessage(code, args, locale)
