package com.github.hu553in.invites_keycloak.bootstrap

import com.github.hu553in.invites_keycloak.bootstrap.config.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class InvitesKeycloakApplicationTests {

    @Test
    fun sampleTest() {
        // sample
    }
}
