package com.github.hu553in.invites_keycloak.controller

import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import java.util.concurrent.TimeUnit

private const val ROBOTS_TXT = "User-agent: *\nDisallow: /\n"

@Controller
class RobotsController {

    @GetMapping("/robots.txt")
    fun robots(): ResponseEntity<String> {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS))
            .contentType(MediaType.TEXT_PLAIN)
            .body(ROBOTS_TXT)
    }
}
