package com.github.hu553in.invites_keycloak.bootstrap

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.github.hu553in.invites_keycloak"])
class InvitesKeycloakApplication

fun main(args: Array<String>) {
    @Suppress("SpreadOperator")
    runApplication<InvitesKeycloakApplication>(*args)
}
