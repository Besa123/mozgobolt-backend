package com.besa.shelflife.feature.user.domain

import com.besa.shelflife.core.domain.AppResult
import com.besa.shelflife.feature.user.domain.model.*

interface UserService {
    suspend fun createUser(
        password: String,
        email: String,
        name: String,
    ): AppResult<Unit, RegisterError>

    suspend fun signInUser(
        password: String,
        email: String,
    ): AppResult<AuthResponse, LoginError>

    suspend fun logoutUser(
        refreshToken: String
    )

    suspend fun logoutAllSessions(userId: Int)

    suspend fun refreshToken(
        oldRefreshToken: String,
    ): AppResult<AuthResponse, RefreshError>

    suspend fun verifyEmail(token: String): AppResult<Unit, VerifyEmailError>

    suspend fun resendVerificationEmail(userId: Int): AppResult<Unit, VerifyEmailError>
}