package com.github.hu553in.invites_keycloak.config

import com.github.hu553in.invites_keycloak.config.props.KeycloakProps
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusScrapeEndpoint
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority
import org.springframework.security.web.SecurityFilterChain

private val healthAndPrometheusMatcher = EndpointRequest.to(
    HealthEndpoint::class.java,
    PrometheusScrapeEndpoint::class.java
)

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val keycloakProps: KeycloakProps
) {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        keycloakAuthoritiesMapper: GrantedAuthoritiesMapper,
        clientRegistrationRepository: ClientRegistrationRepository
    ): SecurityFilterChain {
        return http
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(healthAndPrometheusMatcher).permitAll()
                    .requestMatchers("/", "/favicon.ico", "/invite/**", "/css/**", "/js/**", "/images/**").permitAll()
                    .anyRequest().hasRole(keycloakProps.requiredRole)
            }
            .oauth2Login { oauth2 ->
                oauth2
                    .loginPage("/oauth2/authorization/keycloak")
                    .defaultSuccessUrl("/", true)
                    .userInfoEndpoint { it.userAuthoritiesMapper(keycloakAuthoritiesMapper) }
            }
            .logout { logout ->
                logout
                    .logoutSuccessHandler(
                        OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository)
                            .apply { setPostLogoutRedirectUri("{baseUrl}/") }
                    )
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .deleteCookies("JSESSIONID")
            }
            .csrf { it.ignoringRequestMatchers(healthAndPrometheusMatcher) }
            .build()
    }

    @Bean
    fun keycloakAuthoritiesMapper(): GrantedAuthoritiesMapper {
        return GrantedAuthoritiesMapper { authorities ->
            val out = authorities.toMutableSet()
            val allRoles = authorities.flatMap { a ->
                when (a) {
                    is OidcUserAuthority -> extractRoles(a.idToken.claims, keycloakProps.clientId) +
                        (a.userInfo?.claims?.let { extractRoles(it, keycloakProps.clientId) } ?: emptySet())

                    is OAuth2UserAuthority -> extractRoles(a.attributes, keycloakProps.clientId)
                    else -> emptySet()
                }
            }.toSet()
            out += allRoles.map { SimpleGrantedAuthority("ROLE_$it") }
            out
        }
    }

    private fun extractRoles(claims: Map<String, Any>, clientId: String): Set<String> {
        val out = mutableSetOf<String>()

        val realmAccess = claims["realm_access"] as? Map<*, *>
        val realmRoles = realmAccess?.get("roles") as? Collection<*>
        if (realmRoles != null) {
            out += realmRoles.filterIsInstance<String>().map { it.trim() }.filter { it.isNotBlank() }
        }

        val resourceAccess = claims["resource_access"] as? Map<*, *>
        val clientAccess = resourceAccess?.get(clientId) as? Map<*, *>
        val clientRoles = clientAccess?.get("roles") as? Collection<*>
        if (clientRoles != null) {
            out += clientRoles.filterIsInstance<String>().map { it.trim() }.filter { it.isNotBlank() }
        }

        return out
    }
}
