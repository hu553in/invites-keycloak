package com.github.hu553in.invites_keycloak.bootstrap.config

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        return http
            .csrf(Customizer.withDefaults())
            .authorizeHttpRequests { registry ->
                registry
                    .requestMatchers(
                        "/invite/**",
                        "/v3/api-docs/**",
                        "/v3/api-docs.yaml",
                        "/swagger-ui/**",
                    ).permitAll()
                    .requestMatchers(EndpointRequest.to(HealthEndpoint::class.java)).permitAll()
                    .anyRequest().authenticated()
            }
            .build()
    }
}
