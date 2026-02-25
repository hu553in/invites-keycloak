package com.github.hu553in.invites_keycloak.controller

import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import java.util.concurrent.TimeUnit

@Controller
class FaviconController {

    private val favicon: Resource = ClassPathResource("static/favicon.ico")

    @GetMapping("/favicon.ico")
    fun favicon(): ResponseEntity<Resource> {
        if (!favicon.exists()) {
            return ResponseEntity.notFound().build()
        }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS))
            .contentType(MediaType.parseMediaType("image/x-icon"))
            .body(favicon)
    }
}
