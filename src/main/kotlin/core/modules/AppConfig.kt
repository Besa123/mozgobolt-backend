/*
 * SPDX-License-Identifier: Apache-2.0
 */

// These fields deserialize trusted deployment configuration; this is not an API response model.
@file:Suppress("KotlinxSerializationSensitiveField")

package com.mozgobolt.core.modules

import kotlinx.serialization.Serializable
import java.net.URI

private const val PRODUCTION_ENVIRONMENT = "production"
private const val MIN_PRODUCTION_SECRET_BYTES = 32

@Suppress("HardcodedCredentials") // This is a known placeholder checked to prevent accidental production use.
private const val EXAMPLE_SECRET_PLACEHOLDER = "change-me-to-a-long-random-string"

@Serializable
data class AppConfig(
    val baseUrl: String = "http://localhost:8080",
    val database: Database,
    val jwt: Jwt,
    val security: Security,
    val cors: Cors,
    val email: Email,
    val media: Media = Media(),
    val clamAv: ClamAv = ClamAv(),
    val objectStorage: ObjectStorage = ObjectStorage(),
    val sentry: Sentry = Sentry(),
    val push: Push = Push(),
    val redis: Redis = Redis(),
    val runtimeCapacity: RuntimeCapacityConfig = RuntimeCapacityConfig(),
) {
    @Serializable
    data class Database(
        val url: String,
        val user: String,
        val password: String,
    )

    @Serializable
    data class Jwt(
        val secret: String,
        val issuer: String,
        val audience: String,
        val accessTokenExpiration: Long = 15,
        val refreshTokenExpiration: Long = 10_080,
    )

    @Serializable
    data class Security(
        val passwordPepper: String,
    )

    @Serializable
    data class Cors(
        val allowedHosts: String = "",
    )

    @Serializable
    data class Email(
        val resendApiKey: String = "",
        val fromAddress: String = "noreply@example.com",
        val verificationTokenExpirationHours: Long = 24,
        val resetTokenExpirationMinutes: Long = 15,
    )

    @Serializable
    data class Media(
        val localStorageDirectory: String = "./data/vehicle-images",
        val maxImageDimensionPixels: Int = 2048,
        val maxImagePixelCount: Long = 40_000_000,
        val jpegQuality: Float = 0.85f,
    )

    @Serializable
    data class ClamAv(
        val enabled: Boolean = false,
        val host: String = "localhost",
        val port: Int = 3310,
        val timeoutMs: Long = 5000,
    )

    @Serializable
    data class ObjectStorage(
        val enabled: Boolean = false,
        val endpoint: String = "",
        val bucket: String = "",
        val accessKeyId: String = "",
        val secretAccessKey: String = "",
        val region: String = "auto",
        val apiCallTimeoutMs: Long = 30_000,
        val apiCallAttemptTimeoutMs: Long = 10_000,
    )

    @Serializable
    data class Sentry(
        val dsn: String = "",
    )

    /** [serviceAccountJson] is the raw JSON content of a Firebase service-account key, never a
     * file path — read directly from an env var, same as every other secret in this class, never
     * hardcoded or checked into version control. No separate project-id field: the firebase-admin
     * SDK derives it from the service account's own embedded `project_id`. */
    @Serializable
    data class Push(
        val enabled: Boolean = false,
        val serviceAccountJson: String = "",
    )

    /** Blank [url] (the default) means single-instance mode: every real-time hub falls back to a
     * [com.mozgobolt.core.data.messaging.NoOpMessageRelay], and this app works exactly as it does
     * without Redis at all. Set it only once you actually run more than one instance behind a
     * load balancer and need their in-memory hubs to stay in sync. */
    @Serializable
    data class Redis(
        val url: String = "",
    )
}

fun AppConfig.validateForEnvironment(environment: String) {
    require(runtimeCapacity.databasePoolSize > 0) {
        "DB_POOL_SIZE must be greater than zero"
    }
    require(runtimeCapacity.passwordHashConcurrency > 0) {
        "PASSWORD_HASH_CONCURRENCY must be greater than zero"
    }
    require(runtimeCapacity.imageProcessingConcurrency > 0) {
        "IMAGE_PROCESSING_CONCURRENCY must be greater than zero"
    }

    if (environment != PRODUCTION_ENVIRONMENT) return

    require(jwt.secret.isProductionSecret()) {
        "JWT_SECRET must be a non-placeholder value of at least $MIN_PRODUCTION_SECRET_BYTES bytes in production"
    }
    require(security.passwordPepper.isProductionSecret()) {
        "PASSWORD_PEPPER must be a non-placeholder value of at least $MIN_PRODUCTION_SECRET_BYTES bytes in production"
    }
    require(jwt.secret != security.passwordPepper) {
        "JWT_SECRET and PASSWORD_PEPPER must be different values in production"
    }
    require(clamAv.enabled) {
        "CLAMAV_ENABLED must be true in production for vehicle-picture uploads"
    }

    val uri = URI.create(baseUrl)
    require(
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.query == null &&
            uri.fragment == null,
    ) {
        "APP_BASE_URL must be an absolute HTTPS URL without credentials, query, or fragment in production"
    }
}

private fun String.isProductionSecret(): Boolean =
    this != EXAMPLE_SECRET_PLACEHOLDER && encodeToByteArray().size >= MIN_PRODUCTION_SECRET_BYTES

fun AppConfig.Cors.hosts(): List<String> =
    allowedHosts
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
