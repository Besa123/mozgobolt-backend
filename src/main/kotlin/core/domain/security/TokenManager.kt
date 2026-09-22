package com.mozgobolt.core.domain.security

interface TokenManager {
    fun generateAccessToken(
        userId: Int,
        role: String,
    ): String

    fun generateRefreshToken(userId: Int): String

    fun verifyAndGetUserIdFromRefreshToken(token: String): Int?

    fun hashTokenForStorage(token: String): String
}
