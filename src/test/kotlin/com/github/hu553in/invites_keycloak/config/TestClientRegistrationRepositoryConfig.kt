package com.github.hu553in.invites_keycloak.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod

@Configuration
@Profile("test")
class TestClientRegistrationRepositoryConfig(private val environment: Environment) {

    @Bean
    @Primary
    fun clientRegistrationRepository(): ClientRegistrationRepository {
        val baseUrl = environment.getProperty("keycloak.url") ?: "http://localhost"
        val realm = environment.getProperty("keycloak.realm") ?: "master"
        val realmUrl = "$baseUrl/realms/$realm"

        val clientId = environment.getProperty("keycloak.client-id") ?: "test-client-id"
        val clientSecret = environment.getProperty("keycloak.client-secret") ?: "test-client-secret"

        val registration = ClientRegistration.withRegistrationId("keycloak")
            .clientId(clientId)
            .clientSecret(clientSecret)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope("openid", "profile", "email")
            .authorizationUri("$realmUrl/protocol/openid-connect/auth")
            .tokenUri("$realmUrl/protocol/openid-connect/token")
            .jwkSetUri("$realmUrl/protocol/openid-connect/certs")
            .userInfoUri("$realmUrl/protocol/openid-connect/userinfo")
            .userNameAttributeName("preferred_username")
            .issuerUri(realmUrl)
            .build()

        return InMemoryClientRegistrationRepository(registration)
    }
}
