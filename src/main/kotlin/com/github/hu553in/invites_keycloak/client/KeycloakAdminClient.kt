package com.github.hu553in.invites_keycloak.client

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.hu553in.invites_keycloak.config.props.KeycloakProps
import com.github.hu553in.invites_keycloak.exception.KeycloakAdminClientException
import com.github.hu553in.invites_keycloak.util.ACTIONS_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_EMAIL_KEY
import com.github.hu553in.invites_keycloak.util.INVITE_ROLES_KEY
import com.github.hu553in.invites_keycloak.util.KEYCLOAK_OPERATION_KEY
import com.github.hu553in.invites_keycloak.util.KEYCLOAK_REALM_KEY
import com.github.hu553in.invites_keycloak.util.NANOS_PER_MILLI
import com.github.hu553in.invites_keycloak.util.REQUEST_DURATION_MS_KEY
import com.github.hu553in.invites_keycloak.util.REQUEST_METHOD_KEY
import com.github.hu553in.invites_keycloak.util.REQUEST_REASON_KEY
import com.github.hu553in.invites_keycloak.util.REQUEST_STATUS_KEY
import com.github.hu553in.invites_keycloak.util.REQUEST_URI_KEY
import com.github.hu553in.invites_keycloak.util.RETRY_ATTEMPT_KEY
import com.github.hu553in.invites_keycloak.util.RETRY_MAX_ATTEMPTS_KEY
import com.github.hu553in.invites_keycloak.util.ROLE_COUNT_KEY
import com.github.hu553in.invites_keycloak.util.ROLE_KEY
import com.github.hu553in.invites_keycloak.util.USERNAME_KEY
import com.github.hu553in.invites_keycloak.util.USER_EXISTS_KEY
import com.github.hu553in.invites_keycloak.util.USER_ID_KEY
import com.github.hu553in.invites_keycloak.util.eventForAppError
import com.github.hu553in.invites_keycloak.util.logger
import com.github.hu553in.invites_keycloak.util.maskSensitive
import com.github.hu553in.invites_keycloak.util.normalizeString
import com.github.hu553in.invites_keycloak.util.normalizeStrings
import com.github.hu553in.invites_keycloak.util.withMdc
import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import org.slf4j.spi.LoggingEventBuilder
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
import reactor.util.retry.RetryBackoffSpec
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

    fun executeActionsEmail(realm: String, userId: String, actions: Set<String> = defaultActions)

    fun deleteUser(realm: String, userId: String)

    fun listRealmRoles(realm: String): List<String>
}

@Service
class HttpKeycloakAdminClient(
    private val keycloakProps: KeycloakProps,
    private val clock: Clock,
    webClientBuilder: WebClient.Builder,
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
        return withMdc(
            KEYCLOAK_OPERATION_KEY to "user_exists",
            KEYCLOAK_REALM_KEY to normalizedRealm,
            INVITE_EMAIL_KEY to maskSensitive(normalizedEmail),
        ) {
            val accessToken = obtainAccessToken()

            log.atDebug()
                .log { "Checking if Keycloak user exists" }

            val exists = !executeRequest(
                message = "Failed to verify whether user exists in realm $normalizedRealm",
                block = { retrySpec ->
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
                        .retryWhen(retrySpec)
                        .block()
                },
            ).isNullOrEmpty()

            log.atDebug()
                .addKeyValue(USER_EXISTS_KEY) { exists }
                .log { "Keycloak user existence checked" }

            exists
        }
    }

    override fun createUser(realm: String, email: String, username: String, enabled: Boolean): String {
        val normalizedRealm = normalizeString(realm, "realm must not be blank")
        val normalizedEmail = normalizeString(email, "email must not be blank", lowercase = true)
        val normalizedUsername = normalizeString(username, "username must not be blank")
        return withMdc(
            KEYCLOAK_OPERATION_KEY to "create_user",
            KEYCLOAK_REALM_KEY to normalizedRealm,
            INVITE_EMAIL_KEY to maskSensitive(normalizedEmail),
            USERNAME_KEY to normalizedUsername,
        ) {
            val accessToken = obtainAccessToken()

            val response = executeRequest(
                message = "Failed to create user in realm $normalizedRealm",
                block = { retrySpec ->
                    webClient.post()
                        .uri("/admin/realms/{realm}/users", normalizedRealm)
                        .headers { headers -> headers.setBearerAuth(accessToken) }
                        .contentType(APPLICATION_JSON)
                        .accept(APPLICATION_JSON)
                        .bodyValue(
                            CreateUserRequest(
                                username = normalizedUsername,
                                email = normalizedEmail,
                                enabled = enabled,
                            ),
                        )
                        .retrieve()
                        .onStatus({ it.isSameCodeAs(HttpStatus.CONFLICT) }) {
                            log.atWarn()
                                .log { "Keycloak user already exists; returning conflict" }
                            Mono.error(
                                KeycloakAdminClientException(
                                    message = "User already exists on realm $normalizedRealm",
                                    status = HttpStatus.CONFLICT,
                                ),
                            )
                        }
                        .onStatus({ it.is5xxServerError }) { Mono.error(RetryableException()) }
                        .toBodilessEntity()
                        .retryWhen(retrySpec)
                        .block()
                },
            )

            val userId = extractUserId(response?.headers?.location)
            log.atInfo()
                .addKeyValue(USER_ID_KEY) { userId }
                .log { "Created Keycloak user" }
            userId
        }
    }

    override fun assignRealmRoles(realm: String, userId: String, roles: Set<String>) {
        val normalizedRealm = normalizeString(realm, "realm must not be blank")
        val normalizedUserId = normalizeString(userId, "userId must not be blank")
        val normalizedRoles = normalizeStrings(roles, required = false)
        withMdc(
            KEYCLOAK_OPERATION_KEY to "assign_realm_roles",
            KEYCLOAK_REALM_KEY to normalizedRealm,
            USER_ID_KEY to normalizedUserId,
            INVITE_ROLES_KEY to normalizedRoles.joinToString(","),
        ) {
            if (normalizedRoles.isEmpty()) {
                log.atInfo()
                    .log { "Skipping role assignment because no roles were provided" }
            } else {
                val accessToken = obtainAccessToken()

                val roleRepresentations = normalizedRoles.map { role ->
                    fetchRoleRepresentation(normalizedRealm, role, accessToken)
                }

                executeRequest(
                    message = "Failed to assign roles to user $normalizedUserId in realm $normalizedRealm",
                    block = { retrySpec ->
                        webClient.post()
                            .uri(
                                "/admin/realms/{realm}/users/{userId}/role-mappings/realm",
                                normalizedRealm,
                                normalizedUserId,
                            )
                            .headers { headers -> headers.setBearerAuth(accessToken) }
                            .contentType(APPLICATION_JSON)
                            .accept(APPLICATION_JSON)
                            .bodyValue(roleRepresentations)
                            .retrieve()
                            .onStatus({ it.is5xxServerError }) { Mono.error(RetryableException()) }
                            .toBodilessEntity()
                            .retryWhen(retrySpec)
                            .block()
                    },
                )

                log.atInfo()
                    .log { "Assigned roles to Keycloak user" }
            }
        }
    }

    private fun fetchRoleRepresentation(realm: String, role: String, accessToken: String): RoleRepresentation = withMdc(
        KEYCLOAK_OPERATION_KEY to "fetch_role_representation",
        ROLE_KEY to role,
    ) {
        val representation = executeRequest(
            message = "Failed to resolve role $role in realm $realm",
            block = { retrySpec ->
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
                    .retryWhen(retrySpec)
                    .block()
            },
        ) ?: run {
            log.atError()
                .log { "Role is missing in Keycloak; treating as misconfiguration" }
            throw KeycloakAdminClientException(
                message = "Role $role is not found in realm $realm",
                status = HttpStatus.NOT_FOUND,
            )
        }

        if (representation.id.isNullOrBlank() || representation.name.isNullOrBlank()) {
            log.atError()
                .log { "Role representation from Keycloak is incomplete" }
            throw KeycloakAdminClientException(
                message = "Role $role has incomplete representation in realm $realm",
                status = HttpStatus.BAD_REQUEST,
            )
        }

        representation
    }

    override fun executeActionsEmail(realm: String, userId: String, actions: Set<String>) {
        val normalizedRealm = normalizeString(realm, "realm must not be blank")
        val normalizedUserId = normalizeString(userId, "userId must not be blank")
        val normalizedActions = normalizeStrings(actions, "actions must not be empty")
        withMdc(
            KEYCLOAK_OPERATION_KEY to "execute_actions_email",
            KEYCLOAK_REALM_KEY to normalizedRealm,
            USER_ID_KEY to normalizedUserId,
            ACTIONS_KEY to normalizedActions.joinToString(","),
        ) {
            val accessToken = obtainAccessToken()

            executeRequest(
                message = "Failed to trigger execute-actions-email for user $normalizedUserId " +
                    "in realm $normalizedRealm with actions $actions",
                block = { retrySpec ->
                    webClient.put()
                        .uri(
                            "/admin/realms/{realm}/users/{userId}/execute-actions-email",
                            normalizedRealm,
                            normalizedUserId,
                        )
                        .headers { headers -> headers.setBearerAuth(accessToken) }
                        .contentType(APPLICATION_JSON)
                        .accept(APPLICATION_JSON)
                        .bodyValue(normalizedActions)
                        .retrieve()
                        .onStatus({ it.is5xxServerError }) { Mono.error(RetryableException()) }
                        .toBodilessEntity()
                        .retryWhen(retrySpec)
                        .block()
                },
            )

            log.atInfo()
                .log { "Triggered execute-actions-email for Keycloak user" }
        }
    }

    override fun deleteUser(realm: String, userId: String) {
        val normalizedRealm = normalizeString(realm, "realm must not be blank")
        val normalizedUserId = normalizeString(userId, "userId must not be blank")
        withMdc(
            KEYCLOAK_OPERATION_KEY to "delete_user",
            KEYCLOAK_REALM_KEY to normalizedRealm,
            USER_ID_KEY to normalizedUserId,
        ) {
            val accessToken = obtainAccessToken()

            executeRequest(
                message = "Failed to delete user $normalizedUserId in realm $normalizedRealm",
                block = { retrySpec ->
                    webClient.delete()
                        .uri("/admin/realms/{realm}/users/{userId}", normalizedRealm, normalizedUserId)
                        .headers { headers -> headers.setBearerAuth(accessToken) }
                        .retrieve()
                        .onStatus({ it.is5xxServerError }) { Mono.error(RetryableException()) }
                        .toBodilessEntity()
                        .retryWhen(retrySpec)
                        .block()
                },
            )

            log.atInfo()
                .log { "Deleted Keycloak user" }
        }
    }

    override fun listRealmRoles(realm: String): List<String> {
        val normalizedRealm = normalizeString(realm, "realm must not be blank")
        val accessToken = obtainAccessToken()
        return withMdc(
            KEYCLOAK_OPERATION_KEY to "list_realm_roles",
            KEYCLOAK_REALM_KEY to normalizedRealm,
        ) {
            val allRoles = mutableListOf<RoleRepresentation>()
            var first = 0

            do {
                val page = executeRequest(
                    message = "Failed to list roles for realm $normalizedRealm (page starting at $first)",
                    block = { retrySpec ->
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
                            .retryWhen(retrySpec)
                            .block()
                    },
                ).orEmpty()

                allRoles += page
                first += ROLE_PAGE_SIZE
            } while (page.size == ROLE_PAGE_SIZE)

            val roles = allRoles
                .mapNotNull { it.name?.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()

            log.atDebug()
                .addKeyValue(ROLE_COUNT_KEY) { roles.size }
                .log { "Listed Keycloak realm roles" }

            roles
        }
    }

    private fun retrySpec(message: String): RetryBackoffSpec = Retry
        .backoff(keycloakProps.maxAttempts, keycloakProps.minBackoff)
        .filter { e -> e is RetryableException }
        .doBeforeRetry { signal ->
            log.atDebug()
                .addKeyValue(RETRY_ATTEMPT_KEY) { signal.totalRetries() + 1 }
                .setCause(signal.failure())
                .log { "Retrying Keycloak request after failure" }
        }
        .onRetryExhaustedThrow { _, signal ->
            val failure = signal.failure() ?: RetryableException()
            log.eventForAppError(failure)
                .addKeyValue(RETRY_MAX_ATTEMPTS_KEY) { keycloakProps.maxAttempts }
                .setCause(failure)
                .log { "$message; Keycloak is unavailable after max retries" }
            throw KeycloakAdminClientException(
                "$message; Keycloak is unavailable after max retries",
                HttpStatus.SERVICE_UNAVAILABLE,
                failure,
            )
        }

    private fun <T> executeRequest(message: String, block: (RetryBackoffSpec) -> T): T {
        val start = System.nanoTime()
        return try {
            val result = block(retrySpec(message))
            val durationMs = (System.nanoTime() - start) / NANOS_PER_MILLI
            log.atDebug()
                .addKeyValue(REQUEST_DURATION_MS_KEY) { durationMs }
                .log { "Keycloak request completed" }
            result
        } catch (e: WebClientResponseException) {
            val durationMs = (System.nanoTime() - start) / NANOS_PER_MILLI
            log.eventForAppError(e, keycloakStatus = e.statusCode)
                .addHttpResponseErrorContext(e, durationMs)
                .setCause(e)
                .log { message }
            throw KeycloakAdminClientException(message, e.statusCode, e)
        } catch (e: WebClientRequestException) {
            val durationMs = (System.nanoTime() - start) / NANOS_PER_MILLI
            log.eventForAppError(e)
                .addHttpRequestErrorContext(e, durationMs)
                .setCause(e)
                .log { message }
            throw KeycloakAdminClientException(message, e)
        }
    }

    private fun obtainAccessToken(): String = withMdc(
        KEYCLOAK_OPERATION_KEY to "obtain_access_token",
        KEYCLOAK_REALM_KEY to keycloakProps.realm,
    ) {
        readCachedToken(allowWithinSkew = false)?.value?.also {
            log.atDebug()
                .log { "Using cached Keycloak access token" }
        } ?: run {
            val cachedWithinSkew = readCachedToken(allowWithinSkew = true)
            if (cachedWithinSkew != null) {
                refreshIfPossible(cachedWithinSkew)
            } else {
                refreshWithLock()
            }
        }
    }

    private fun refreshIfPossible(cachedWithinSkew: CachedAccessToken): String {
        if (!cachedAccessTokenWriteLock.tryLock()) {
            log.atDebug()
                .log { "Using cached Keycloak access token within skew window" }
            return cachedWithinSkew.value
        }

        val refreshed = try {
            readCachedToken(allowWithinSkew = false)?.value ?: fetchAndCacheAccessToken()
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception,
        ) {
            if (isTokenUsable(cachedWithinSkew, allowWithinSkew = true)) {
                log.atWarn()
                    .setCause(e)
                    .log { "Failed to refresh Keycloak access token; using cached token within skew window" }
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
            log.atDebug()
                .log { "Refreshing Keycloak access token" }
            readCachedToken(allowWithinSkew = false)?.value ?: fetchAndCacheAccessToken()
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception,
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
            message = "Failed to obtain access token for realm ${keycloakProps.realm}",
            block = { retrySpec ->
                webClient.post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", keycloakProps.realm)
                    .contentType(APPLICATION_FORM_URLENCODED)
                    .accept(APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus({ it.is5xxServerError }) { Mono.error(RetryableException()) }
                    .bodyToMono(TokenResponse::class.java)
                    .retryWhen(retrySpec)
                    .block()
            },
        )

        val accessToken = response?.accessToken
        if (accessToken.isNullOrBlank()) {
            log.atError()
                .log { "Keycloak returned an empty access token" }
            throw KeycloakAdminClientException(
                "Keycloak returned an empty access token for realm ${keycloakProps.realm}",
            )
        }

        cachedAccessToken = response.expiresIn?.let { CachedAccessToken(accessToken, now + it) }
        log.atDebug()
            .log { "Fetched new Keycloak access token" }
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
        val expiresIn: Long? = null,
    )

    private data class CreateUserRequest(val username: String, val email: String, val enabled: Boolean)

    private data class RoleRepresentation(val id: String?, val name: String?)

    private data class UserRepresentation(val id: String?, val email: String?, val username: String?)

    private data class CachedAccessToken(val value: String, val expiresAtEpochSec: Long?)

    private class RetryableException : RuntimeException()
}

private fun LoggingEventBuilder.addHttpResponseErrorContext(
    e: WebClientResponseException,
    durationMs: Long,
): LoggingEventBuilder = this
    .addKeyValue(REQUEST_STATUS_KEY) { e.statusCode.value() }
    .addKeyValue(REQUEST_REASON_KEY) { e.statusText }
    .addKeyValue(REQUEST_DURATION_MS_KEY) { durationMs }

private fun LoggingEventBuilder.addHttpRequestErrorContext(
    e: WebClientRequestException,
    durationMs: Long,
): LoggingEventBuilder = this
    .addKeyValue(REQUEST_URI_KEY) { e.uri }
    .addKeyValue(REQUEST_METHOD_KEY) { e.method }
    .addKeyValue(REQUEST_DURATION_MS_KEY) { durationMs }

private fun extractUserId(location: URI?): String {
    val userId = location?.path?.substringAfterLast('/')?.trim()
    if (userId.isNullOrBlank()) {
        throw KeycloakAdminClientException("Unable to resolve user id from Location header: $location")
    }
    return userId
}

private fun Long.toPositiveIntWithinIntMax(): Int = coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
