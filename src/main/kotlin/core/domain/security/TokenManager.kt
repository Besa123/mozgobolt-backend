package com.besa.boardShare.core.domain.security

interface TokenManager {
    fun generateAccessToken(userId: Int, email: String): String
    fun generateRefreshToken(userId: Int): String
}