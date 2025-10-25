package com.github.hu553in.invites_keycloak.bootstrap.config

import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationPropertiesScan(basePackages = ["com.github.hu553in.invites_keycloak"])
class BootstrapConfig
