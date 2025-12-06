package com.github.hu553in.invites_keycloak.controller

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

private const val REDIRECT = "redirect:/admin/invite"

@Controller
class RootRedirectController {
    @GetMapping("/")
    fun redirectToAdmin(): String = REDIRECT
}
