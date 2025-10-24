package com.github.hu553in.invites_keycloak

import org.springframework.boot.fromApplication
import org.springframework.boot.with

fun main(args: Array<String>) {
    fromApplication<InvitesKeycloakApplication>().with(TestcontainersConfiguration::class).run(*args)
}
