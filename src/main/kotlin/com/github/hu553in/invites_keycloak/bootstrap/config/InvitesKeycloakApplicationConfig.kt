package com.github.hu553in.invites_keycloak.bootstrap.config

import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

const val BASE_PACKAGE = "com.github.hu553in.invites_keycloak"

@Configuration
@EnableScheduling
@ConfigurationPropertiesScan(basePackages = [BASE_PACKAGE])
@EnableJpaRepositories(basePackages = [BASE_PACKAGE])
@EntityScan(basePackages = [BASE_PACKAGE])
class InvitesKeycloakApplicationConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
