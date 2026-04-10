package com.github.hu553in.invites_keycloak.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.LocaleResolver
import org.springframework.web.servlet.i18n.FixedLocaleResolver
import java.util.*

@Configuration
class LocaleConfig(@param:Value($$"${app.locale:en}") private val localeTag: String) {

    @Bean
    fun localeResolver(): LocaleResolver = FixedLocaleResolver(resolveLocale(localeTag))

    private fun resolveLocale(tag: String): Locale {
        val normalized = tag.trim()
        if (normalized.isBlank()) {
            return Locale.ENGLISH
        }

        val locale = Locale.forLanguageTag(normalized)
        return if (locale.language.isBlank()) {
            Locale.ENGLISH
        } else {
            locale
        }
    }
}
