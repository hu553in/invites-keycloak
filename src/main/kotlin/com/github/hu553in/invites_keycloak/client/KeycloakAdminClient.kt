package com.github.hu553in.invites_keycloak.client

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.hu553in.invites_keycloak.config.props.KeycloakProps
import com.github.hu553in.invites_keycloak.exception.KeycloakAdminClientException
import com.github.hu553in.invites_keycloak.util.logger
import com.github.hu553in.invites_keycloak.util.normalizeString
import com.github.hu553in.invites_keycloak.util.normalizeStrings
import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import reactor.util.retry.Retry
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

interface KeycloakAdminClient {

    companion object {
        val defaultActions = setOf("UPDATE_PASSWORD", "VERIFY_EMAIL")
    }

    fun userExists(realm: String, email: String): Boolean

    fun createUser(realm: String, email: String, username: String, enabled: Boolean = true): String

    fun assignRealmRoles(realm: String, userId: String, roles: Set<String>)

    fun executeActionsEmail(
        realm: String,
        userId: String,
        actions: Set<String> = defaultActions
    )
}

@Service
class HttpKeycloakAdminClient(
    private val keycloakProps: KeycloakProps,
    private val clock: Clock,
    webClientBuilder: WebClient.Builder
) : KeycloakAdminClient {

    companion object {
        private val userListType = object : ParameterizedTypeReference<List<UserRepresentation>>() {}

        private const val ACCESS_TOKEN_SKEW_SECONDS = 60

        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val RESPONSE_TIMEOUT_SECONDS = 10L
    }

    private val log by logger()

    private val retry = Retry
        .backoff(keycloakProps.maxAttempts, keycloakProps.minBackoff)
        .filter { e -> e is RetryableException }
        .onRetryExhaustedThrow { _, _ ->
            throw KeycloakAdminClientException(
                "Keycloak is unavailable after max retries",
                HttpStatus.SERVICE_UNAVAILABLE
            )
        }

    private val httpClient = HttpClient.create()
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
        .responseTimeout(Duration.ofSeconds(RESPONSE_TIMEOUT_SECONDS))
        .doOnConnected { connection ->
            connection
                .addHandlerLast(ReadTimeoutHandler(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .addHandlerLast(WriteTimeoutHandler(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }

    private val webClient: WebClient = webClientBuilder
        .clone()
        .baseUrl(keycloakProps.url.trimEnd('/'))
        .clientConnector(ReactorClientHttpConnector(httpClient))
        .build()

    private var cachedAccessToken: CachedAccessToken? = null

    @Suppress("ForbiddenComment")
    // TODO: replace with ReentrantReadWriteLock
    private val cachedAccessTokenLock = ReentrantLock()

    override fun userExists(realm: String, email: String): Boolean {
        val normalizedRealm = normalizeString(realm, "realm must not be blank")
        val normalizedEmail = normalizeString(email, "email must not be blank", lowercase = true)
        val accessToken = obtainAccessToken()

        return !execute(
            ctx = "Failed to verify whether user exists in realm $normalizedRealm",
            block = {
                webClient.get()
                    .uri { builder ->
                        builder
                            .path("/admin/realms/{realm}/users")
                            .queryParam("email", normalizedEmail)
                            .queryParam("exact", true)
                            .queryParam("max", 1)
                            .queryParam("briefRepresentation", true)
                            .build(normalizedRealm)
                    }
                    .headers { headers -> headers.setBearerAuth(accessToken) }
                    .accept(APPLICATION_JSON)
                    .retrieve()
                    .onStatus({ it.is5xxServerError }) { Mono.error(RetryableException()) }
                    .bodyToMono(userListType)
                    .retryWhen(retry)
                    .block()
            }
        ).isNullOrEmpty()
    }

    override fun createUser(realm: String, email: String, username: String, enabled: Boolean): String {
        val normalizedRealm = normalizeString(realm, "realm must not be blank")
        val normalizedEmail = normalizeString(email, "email must not be blank", lowercase = true)
        val normalizedUsername = normalizeString(username, "username must not be blank")
        val accessToken = obtainAccessToken()

        val response = execute(
            ctx = "Failed to create user in realm $normalizedRealm",
            block = {
                webClient.post()
                    .uri("/admin/realms/{realm}/users", normalizedRealm)
                    .headers { headers -> headers.setBearerAuth(accessToken) }
                    .contentType(APPLICATION_JSON)
                    .accept(APPLICATION_JSON)
                    .bodyValue(
                        CreateUserRequest(
                            username = normalizedUsername,
                            email = normalizedEmail,
                            enabled = enabled
                        )
                    )
                    .retrieve()
                    .onStatus({ it.isSameCodeAs(HttpStatus.CONFLICT) }) {
                        Mono.error(KeycloakAdminClientException("User already exists on realm $normalizedRealm"))
                    }
                    .onStatus({ it.is5xxServerError }) { Mono.error(RetryableException()) }
                    .toBodilessEntity()
                    .retryWhen(retry)
                    .block()
            }
        )

        val userId = extractUserId(response?.headers?.location)
        log.atInfo()
            .addKeyValue("user.id") { userId }
            .addKeyValue("realm") { normalizedRealm }
            .log { "Created Keycloak user" }
        return userId
    }

    override fun assignRealmRoles(realm: String, userId: String, roles: Set<String>) {
        val normalizedRealm = normalizeString(realm, "realm must not be blank")
        val normalizedUserId = normalizeString(userId, "userId must not be blank")
        val normalizedRoles = normalizeStrings(roles, "roles must not be empty")
        val accessToken = obtainAccessToken()

        val roleRepresentations = normalizedRoles.map { role ->
            execute(
                ctx = "Failed to resolve role $role in realm $normalizedRealm",
                block = {
                    webClient.get()
                        .uri("/admin/realms/{realm}/roles/{role}", normalizedRealm, role)
                        .headers { headers -> headers.setBearerAuth(accessToken) }
                        .accept(APPLICATION_JSON)
                        .exchangeToMono { resp ->
                            when {
                                resp.statusCode().is2xxSuccessful -> resp.bodyToMono(RoleRepresentation::class.java)
                                resp.statusCode().isSameCodeAs(HttpStatus.NOT_FOUND) -> Mono.empty()
                                resp.statusCode().is5xxServerError -> Mono.error(RetryableException())
                                else -> resp.createException().flatMap { Mono.error(it) }
                            }
                        }
                        .retryWhen(retry)
                        .block()
                }
            )?.also {
                if (it.id.isNullOrBlank() || it.name.isNullOrBlank()) {
                    throw KeycloakAdminClientException(
                        "Role $role has incomplete representation in realm $normalizedRealm"
                    )
                }
            } ?: throw KeycloakAdminClientException("Role $role is not found in realm $normalizedRealm")
        }

        execute(
            ctx = "Failed to assign roles to user $normalizedUserId in realm $normalizedRealm",
            block = {
                webClient.post()
                    .uri(
                        "/admin/realms/{realm}/users/{userId}/role-mappings/realm",
                        normalizedRealm,
                        normalizedUserId
                    )
                    .headers { headers -> headers.setBearerAuth(accessToken) }
                    .contentType(APPLICATION_JSON)
                    .accept(APPLICATION_JSON)
                    .bodyValue(roleRepresentations)
                    .retrieve()
                    .onStatus({ it.is5xxServerError }) { Mono.error(RetryableException()) }
                    .toBodilessEntity()
                    .retryWhen(retry)
                    .block()
            }
        )

        log.atInfo()
            .addKeyValue("roles") { roleRepresentations.mapNotNull { it.name }.joinToString(", ") }
            .addKeyValue("user.id") { normalizedUserId }
            .addKeyValue("realm") { normalizedRealm }
            .log { "Assigned roles to Keycloak user" }
    }

    override fun executeActionsEmail(realm: String, userId: String, actions: Set<String>) {
        val normalizedRealm = normalizeString(realm, "realm must not be blank")
        val normalizedUserId = normalizeString(userId, "userId must not be blank")
        val normalizedActions = normalizeStrings(actions, "actions must not be empty")
        val accessToken = obtainAccessToken()

        execute(
            ctx = "Failed to trigger execute-actions-email for user $normalizedUserId " +
                "in realm $normalizedRealm with actions $actions",
            block = {
                webClient.put()
                    .uri(
                        "/admin/realms/{realm}/users/{userId}/execute-actions-email",
                        normalizedRealm,
                        normalizedUserId
                    )
                    .headers { headers -> headers.setBearerAuth(accessToken) }
                    .contentType(APPLICATION_JSON)
                    .accept(APPLICATION_JSON)
                    .bodyValue(normalizedActions)
                    .retrieve()
                    .onStatus({ it.is5xxServerError }) { Mono.error(RetryableException()) }
                    .toBodilessEntity()
                    .retryWhen(retry)
                    .block()
            }
        )

        log.atInfo()
            .addKeyValue("actions") { normalizedActions.joinToString(", ") }
            .addKeyValue("user.id") { normalizedUserId }
            .addKeyValue("realm") { normalizedRealm }
            .log { "Triggered execute-actions-email for Keycloak user" }
    }

    private fun obtainAccessToken(): String {
        cachedAccessTokenLock.lock()
        try {
            val now = clock.instant().epochSecond
            cachedAccessToken?.let { (accessToken, expiresAt) ->
                if (expiresAt != null && now < expiresAt - ACCESS_TOKEN_SKEW_SECONDS) {
                    return accessToken
                }
            }

            val body = BodyInserters
                .fromFormData("grant_type", "client_credentials")
                .with("client_id", keycloakProps.clientId)
                .with("client_secret", keycloakProps.clientSecret)

            val response = execute(
                ctx = "Failed to obtain access token for realm ${keycloakProps.realm}",
                block = {
                    webClient.post()
                        .uri("/realms/{realm}/protocol/openid-connect/token", keycloakProps.realm)
                        .contentType(APPLICATION_FORM_URLENCODED)
                        .accept(APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .onStatus({ it.is5xxServerError }) { Mono.error(RetryableException()) }
                        .bodyToMono(TokenResponse::class.java)
                        .retryWhen(retry)
                        .block()
                }
            )

            val accessToken = response?.accessToken
            if (accessToken.isNullOrBlank()) {
                throw KeycloakAdminClientException(
                    "Keycloak returned an empty access token for realm ${keycloakProps.realm}"
                )
            }

            cachedAccessToken = response.expiresIn?.let { CachedAccessToken(accessToken, now + it) }
            return accessToken
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception
        ) {
            cachedAccessToken = null
            throw e
        } finally {
            cachedAccessTokenLock.unlock()
        }
    }

    private fun extractUserId(location: URI?): String {
        val userId = location?.path?.substringAfterLast('/')?.trim()
        if (userId.isNullOrBlank()) {
            throw KeycloakAdminClientException("Unable to resolve user id from Location header: $location")
        }
        return userId
    }

    private fun <T> execute(ctx: String, block: () -> T): T {
        return try {
            block()
        } catch (e: WebClientResponseException) {
            throw KeycloakAdminClientException(ctx, e.statusCode, e)
        } catch (e: WebClientRequestException) {
            throw KeycloakAdminClientException(ctx, e)
        }
    }

    private data class TokenResponse(
        @field:JsonProperty("access_token")
        val accessToken: String?,
        @field:JsonProperty("expires_in")
        val expiresIn: Long? = null
    )

    private data class CreateUserRequest(
        val username: String,
        val email: String,
        val enabled: Boolean
    )

    private data class RoleRepresentation(
        val id: String?,
        val name: String?
    )

    private data class UserRepresentation(
        val id: String?,
        val email: String?,
        val username: String?
    )

    private data class CachedAccessToken(val value: String, val expiresAtEpochSec: Long?)

    private class RetryableException : RuntimeException()
}
