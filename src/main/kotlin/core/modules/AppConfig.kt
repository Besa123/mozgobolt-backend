package com.besa.boardShare.core.modules

import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    val database: Database,
    val jwt: Jwt,
    val security: Security,
    val cors: Cors,
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
        val refreshTokenExpiration: Long = 10080,
    )

    @Serializable
    data class Security(
        val passwordPepper: String,
    )

    @Serializable
    data class Cors(
        val allowedHosts: String = "",
    ) {
        fun hosts(): List<String> = allowedHosts
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
}
