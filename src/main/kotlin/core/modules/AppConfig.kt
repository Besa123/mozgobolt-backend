package com.mozgobolt.core.modules

import kotlinx.serialization.Serializable

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
) {
    @Serializable
    data class Database(
        val url: String,
        val user: String,
        val password: String,
        val poolSize: Int = 10,
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
    ) {
        fun hosts(): List<String> =
            allowedHosts
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
    }

    @Serializable
    data class Email(
        val resendApiKey: String = "",
        val fromAddress: String = "noreply@example.com",
        val verificationTokenExpirationHours: Long = 24,
        val resetTokenExpirationMinutes: Long = 15,
    )

    @Serializable
    data class Media(
        val localStorageDirectory: String = "./data/pantry-entry-images",
        val maxImagesPerEntry: Int = 3,
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
