package com.shelflife.core.modules

import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    val baseUrl: String = "http://localhost:8080",
    val database: Database,
    val jwt: Jwt,
    val security: Security,
    val cors: Cors,
    val email: Email,
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
}
