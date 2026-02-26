import dev.detekt.gradle.plugin.getSupportedKotlinVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.springframework.boot.gradle.tasks.bundling.BootBuildImage

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.kotlin.noarg)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    alias(libs.plugins.versions)
    alias(libs.plugins.axion.release)
}

group = "com.github.hu553in"
version = "1.0.0"
description = "Invites for your Keycloak"

scmVersion {
    sanitizeVersion = true
    checks {
        uncommittedChanges.set(true)
        aheadOfRemote.set(false)
        snapshotDependencies.set(true)
    }
    releaseBranchNames.set(setOf("main"))
    releaseOnlyOnReleaseBranches = true
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.spring.boot.starter.oauth2.client)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.thymeleaf)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.webclient)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.kotlin.reflect)
    implementation(libs.thymeleaf.extras.springsecurity6)
    implementation(libs.spring.dotenv)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.opentelemetry.exporter.otlp)

    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.data.jpa.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.boot.starter.flyway.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.wiremock.standalone)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)

    testRuntimeOnly(libs.junit.platform.launcher)

    detektPlugins(libs.detekt.ktlint)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

noArg {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("spring.profiles.active", "test")
}

detekt {
    buildUponDefaultConfig = true
    parallel = true
    autoCorrect = project.hasProperty("detektAutoCorrect")
    config.setFrom(files("$rootDir/config/detekt.yml"))
}

configurations.matching { it.name == "detekt" }.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            val kotlinVersion = getSupportedKotlinVersion()
            useVersion(kotlinVersion)
            because("detekt ${libs.versions.detekt.get()} requires Kotlin $kotlinVersion")
        }
    }
}

kover {
    reports {
        filters {
            excludes {
                classes("InvitesKeycloakApplication")
            }
        }

        total {
            xml { onCheck = true }
            html { onCheck = true }

            verify {
                rule {
                    minBound(80)
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn("detekt")
    dependsOn("koverVerify")
    dependsOn("koverHtmlReport")
    dependsOn("koverXmlReport")
}

tasks.named<BootBuildImage>("bootBuildImage") {
    environment.put("BP_HEALTH_CHECKER_ENABLED", "true")
    buildpacks.set(
        listOf(
            "urn:cnb:builder:paketo-buildpacks/java",
            "docker.io/paketobuildpacks/health-checker:latest"
        )
    )
}
