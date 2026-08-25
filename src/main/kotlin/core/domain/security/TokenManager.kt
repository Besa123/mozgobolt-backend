package com.besa.shelflife.core.domain.security

interface TokenManager {
    fun generateAccessToken(userId: Int): String

    fun generateRefreshToken(userId: Int): String

    fun verifyAndGetUserIdFromRefreshToken(token: String): Int?

    fun hashTokenForStorage(token: String): String
}
