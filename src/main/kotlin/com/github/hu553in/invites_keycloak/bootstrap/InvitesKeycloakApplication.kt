package com.github.hu553in.invites_keycloak.bootstrap

import com.github.hu553in.invites_keycloak.bootstrap.config.BASE_PACKAGE
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = [BASE_PACKAGE])
class InvitesKeycloakApplication

fun main(args: Array<String>) {
    @Suppress("SpreadOperator")
    runApplication<InvitesKeycloakApplication>(*args)
}
