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
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.max
import kotlin.math.min

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

    fun deleteUser(realm: String, userId: String)

    fun listRealmRoles(realm: String): List<String>
}

@Service
class HttpKeycloakAdminClient(
    private val keycloakProps: KeycloakProps,
    private val clock: Clock,
    webClientBuilder: WebClient.Builder
) : KeycloakAdminClient {

    companion object {
        private val userListType = object : ParameterizedTypeReference<List<UserRepresentation>>() {}
        private val roleListType = object : ParameterizedTypeReference<List<RoleRepresentation>>() {}

        private const val ACCESS_TOKEN_SKEW_SECONDS = 60
        private const val MIN_ACCESS_TOKEN_SKEW_SECONDS = 5L
        private const val ROLE_PAGE_SIZE = 1_000
    }

    private val log by logger()

    private val connectTimeoutMillis: Int = keycloakProps.connectTimeout.toMillis().toPositiveIntWithinIntMax()
    private val responseTimeoutSeconds: Int = keycloakProps.responseTimeout.seconds.toPositiveIntWithinIntMax()

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
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
        .responseTimeout(keycloakProps.responseTimeout)
        .doOnConnected { connection ->
            connection
                .addHandlerLast(ReadTimeoutHandler(responseTimeoutSeconds))
                .addHandlerLast(WriteTimeoutHandler(responseTimeoutSeconds))
        }

    private val webClient: WebClient = webClientBuilder
        .clone()
        .baseUrl(keycloakProps.url.trimEnd('/'))
        .clientConnector(ReactorClientHttpConnector(httpClient))
        .build()

    private var cachedAccessToken: CachedAccessToken? = null

    private val cachedAccessTokenLock = ReentrantReadWriteLock()
    private val cachedAccessTokenReadLock = cachedAccessTokenLock.readLock()
    private val cachedAccessTokenWriteLock = cachedAccessTokenLock.writeLock()

    override fun userExists(realm: String, email: String): Boolean {
        val normalizedRealm = normalizeString(realm, "realm must not be blank")
        val normalizedEmail = normalizeString(email, "email must not be blank", lowercase = true)
        val accessToken = obtainAccessToken()

        return !executeRequest(
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

        val response = executeRequest(
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
                        Mono.error(
                            KeycloakAdminClientException(
                                message = "User already exists on realm $normalizedRealm",
                                status = HttpStatus.CONFLICT
                            )
                        )
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
        val normalizedRoles = normalizeStrings(roles, required = false)
        if (normalizedRoles.isEmpty()) {
            log.atInfo()
                .addKeyValue("user.id") { normalizedUserId }
                .addKeyValue("realm") { normalizedRealm }
                .log { "Skipping role assignment because no roles were provided" }
            return
        }
        val accessToken = obtainAccessToken()

        val roleRepresentations = normalizedRoles.map { role ->
            fetchRoleRepresentation(normalizedRealm, role, accessToken)
        }

        executeRequest(
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

    private fun fetchRoleRepresentation(realm: String, role: String, accessToken: String): RoleRepresentation {
        val representation = executeRequest(
            ctx = "Failed to resolve role $role in realm $realm",
            block = {
                webClient.get()
                    .uri("/admin/realms/{realm}/roles/{role}", realm, role)
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
        ) ?: throw KeycloakAdminClientException(
            message = "Role $role is not found in realm $realm",
            status = HttpStatus.NOT_FOUND
        )

        if (representation.id.isNullOrBlank() || representation.name.isNullOrBlank()) {
            throw KeycloakAdminClientException(
                message = "Role $role has incomplete representation in realm $realm",
                status = HttpStatus.BAD_REQUEST
            )
        }

        return representation
    }

    override fun executeActionsEmail(realm: String, userId: String, actions: Set<String>) {
        val normalizedRealm = normalizeString(realm, "realm must not be blank")
        val normalizedUserId = normalizeString(userId, "userId must not be blank")
        val normalizedActions = normalizeStrings(actions, "actions must not be empty")
        val accessToken = obtainAccessToken()

        executeRequest(
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

    override fun deleteUser(realm: String, userId: String) {
        val normalizedRealm = normalizeString(realm, "realm must not be blank")
        val normalizedUserId = normalizeString(userId, "userId must not be blank")
        val accessToken = obtainAccessToken()

        executeRequest(
            ctx = "Failed to delete user $normalizedUserId in realm $normalizedRealm",
            block = {
                webClient.delete()
                    .uri("/admin/realms/{realm}/users/{userId}", normalizedRealm, normalizedUserId)
                    .headers { headers -> headers.setBearerAuth(accessToken) }
                    .retrieve()
                    .onStatus({ it.is5xxServerError }) { Mono.error(RetryableException()) }
                    .toBodilessEntity()
                    .retryWhen(retry)
                    .block()
            }
        )

        log.atInfo()
            .addKeyValue("user.id") { normalizedUserId }
            .addKeyValue("realm") { normalizedRealm }
            .log { "Deleted Keycloak user" }
    }

    override fun listRealmRoles(realm: String): List<String> {
        val normalizedRealm = normalizeString(realm, "realm must not be blank")
        val accessToken = obtainAccessToken()

        val allRoles = mutableListOf<RoleRepresentation>()
        var first = 0

        do {
            val page = executeRequest(
                ctx = "Failed to list roles for realm $normalizedRealm (page starting at $first)",
                block = {
                    webClient.get()
                        .uri { builder ->
                            builder
                                .path("/admin/realms/{realm}/roles")
                                .queryParam("first", first)
                                .queryParam("max", ROLE_PAGE_SIZE)
                                .build(normalizedRealm)
                        }
                        .headers { headers -> headers.setBearerAuth(accessToken) }
                        .accept(APPLICATION_JSON)
                        .retrieve()
                        .onStatus({ it.is5xxServerError }) { Mono.error(RetryableException()) }
                        .bodyToMono(roleListType)
                        .retryWhen(retry)
                        .block()
                }
            ).orEmpty()

            allRoles += page
            first += ROLE_PAGE_SIZE
        } while (page.size == ROLE_PAGE_SIZE)

        return allRoles
            .mapNotNull { it.name?.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    private fun obtainAccessToken(): String {
        readCachedToken(allowWithinSkew = false)?.value?.let { return it }

        val cachedWithinSkew = readCachedToken(allowWithinSkew = true)
        val token = if (cachedWithinSkew != null) {
            refreshIfPossible(cachedWithinSkew)
        } else {
            refreshWithLock()
        }
        return token
    }

    private fun refreshIfPossible(cachedWithinSkew: CachedAccessToken): String {
        if (!cachedAccessTokenWriteLock.tryLock()) {
            return cachedWithinSkew.value
        }

        val refreshed = try {
            readCachedToken(allowWithinSkew = false)?.value ?: fetchAndCacheAccessToken()
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception
        ) {
            if (isTokenUsable(cachedWithinSkew, allowWithinSkew = true)) {
                cachedWithinSkew.value
            } else {
                cachedAccessToken = null
                throw e
            }
        } finally {
            cachedAccessTokenWriteLock.unlock()
        }

        return refreshed
    }

    private fun refreshWithLock(): String {
        cachedAccessTokenWriteLock.lock()
        return try {
            readCachedToken(allowWithinSkew = false)?.value ?: fetchAndCacheAccessToken()
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception
        ) {
            cachedAccessToken = null
            throw e
        } finally {
            cachedAccessTokenWriteLock.unlock()
        }
    }

    private fun fetchAndCacheAccessToken(): String {
        val now = clock.instant().epochSecond
        val body = BodyInserters
            .fromFormData("grant_type", "client_credentials")
            .with("client_id", keycloakProps.clientId)
            .with("client_secret", keycloakProps.clientSecret)

        val response = executeRequest(
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
    }

    private fun readCachedToken(allowWithinSkew: Boolean): CachedAccessToken? {
        cachedAccessTokenReadLock.lock()
        return try {
            cachedAccessToken?.takeIf { isTokenUsable(it, allowWithinSkew) }
        } finally {
            cachedAccessTokenReadLock.unlock()
        }
    }

    private fun isTokenUsable(cachedToken: CachedAccessToken, allowWithinSkew: Boolean): Boolean {
        val expiresAt = cachedToken.expiresAtEpochSec ?: return false
        val now = clock.instant().epochSecond
        val remainingSeconds = expiresAt - now
        val skewSeconds = computeSkewSeconds(remainingSeconds)
        val meetsSkew = allowWithinSkew || remainingSeconds > skewSeconds
        return remainingSeconds > 0 && meetsSkew
    }

    private fun computeSkewSeconds(remainingSeconds: Long): Long {
        val halfLifetime = remainingSeconds / 2
        val adjustedSkew = max(MIN_ACCESS_TOKEN_SKEW_SECONDS, halfLifetime)
        return min(ACCESS_TOKEN_SKEW_SECONDS.toLong(), adjustedSkew)
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

private fun extractUserId(location: URI?): String {
    val userId = location?.path?.substringAfterLast('/')?.trim()
    if (userId.isNullOrBlank()) {
        throw KeycloakAdminClientException("Unable to resolve user id from Location header: $location")
    }
    return userId
}

private fun <T> executeRequest(ctx: String, block: () -> T): T {
    return try {
        block()
    } catch (e: WebClientResponseException) {
        throw KeycloakAdminClientException(ctx, e.statusCode, e)
    } catch (e: WebClientRequestException) {
        throw KeycloakAdminClientException(ctx, e)
    }
}

private fun Long.toPositiveIntWithinIntMax(): Int {
    return coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
}
