package com.github.hu553in.invites_keycloak.bootstrap.config

import com.github.hu553in.invites_keycloak.features.keycloak.config.KeycloakProps
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val keycloakProps: KeycloakProps
) {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        keycloakAuthoritiesMapper: GrantedAuthoritiesMapper
    ): SecurityFilterChain {
        return http
            .csrf(Customizer.withDefaults())
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        "/invite/**",
                        "/v3/api-docs/**",
                        "/v3/api-docs.yaml",
                        "/swagger-ui/**",
                    ).permitAll()
                    .requestMatchers(EndpointRequest.to(HealthEndpoint::class.java)).permitAll()
                    .requestMatchers("/admin/**").hasRole(keycloakProps.requiredRole)
                    .anyRequest().authenticated()
            }
            .oauth2Login { oauth2 ->
                oauth2.userInfoEndpoint { userInfo ->
                    userInfo.userAuthoritiesMapper(keycloakAuthoritiesMapper)
                }
            }
            .build()
    }

    @Bean
    fun keycloakAuthoritiesMapper(): GrantedAuthoritiesMapper {
        val requiredAuthority = SimpleGrantedAuthority("ROLE_${keycloakProps.requiredRole}")

        return GrantedAuthoritiesMapper { authorities ->
            val mappedAuthorities = authorities.toMutableSet()
            val roles = mutableSetOf<String>()

            authorities.forEach { authority ->
                when (authority) {
                    is OidcUserAuthority -> {
                        roles += extractRoles(authority.idToken.claims)
                        authority.userInfo?.claims?.let { claims -> roles += extractRoles(claims) }
                    }

                    is OAuth2UserAuthority -> {
                        roles += extractRoles(authority.attributes)
                    }
                }
            }

            if (keycloakProps.requiredRole in roles) {
                mappedAuthorities += requiredAuthority
            }

            mappedAuthorities
        }
    }

    @Bean
    fun clientRegistrationRepository(): ClientRegistrationRepository {
        return InMemoryClientRegistrationRepository(keycloakClientRegistration())
    }

    @Bean
    fun authorizedClientService(
        clientRegistrationRepository: ClientRegistrationRepository
    ): OAuth2AuthorizedClientService {
        return InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository)
    }

    private fun keycloakClientRegistration(): ClientRegistration {
        val keycloakRealmUrl = "${keycloakProps.url.trimEnd('/')}/realms/${keycloakProps.realm}"
        return ClientRegistration.withRegistrationId("keycloak")
            .clientId(keycloakProps.clientId)
            .clientSecret(keycloakProps.clientSecret)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope("openid", "profile", "email")
            .authorizationUri("$keycloakRealmUrl/protocol/openid-connect/auth")
            .tokenUri("$keycloakRealmUrl/protocol/openid-connect/token")
            .userInfoUri("$keycloakRealmUrl/protocol/openid-connect/userinfo")
            .userNameAttributeName("preferred_username")
            .jwkSetUri("$keycloakRealmUrl/protocol/openid-connect/certs")
            .issuerUri(keycloakRealmUrl)
            .build()
    }

    private fun extractRoles(claims: Map<String, Any>): Set<String> {
        val roles = when (val realmAccess = claims["realm_access"]) {
            is Map<*, *> -> realmAccess["roles"]
            else -> null
        }

        return when (roles) {
            is Collection<*> ->
                roles
                    .filterIsInstance<String>()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()

            else -> emptySet()
        }
    }
}
