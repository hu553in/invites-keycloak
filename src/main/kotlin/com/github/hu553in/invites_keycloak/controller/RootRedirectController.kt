package com.github.hu553in.invites_keycloak.controller

import com.github.hu553in.invites_keycloak.util.logger
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

private const val REDIRECT = "redirect:/admin/invite"

@Controller
class RootRedirectController {

    private val log by logger()

    @GetMapping("/")
    fun redirectToAdmin(): String {
        log.atDebug().log { "Redirecting root to admin invite list" }
        return REDIRECT
    }
}
