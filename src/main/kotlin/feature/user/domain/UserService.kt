package com.shelflife.feature.user.domain

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.user.domain.model.AuthResponse
import com.shelflife.feature.user.domain.model.LoginError
import com.shelflife.feature.user.domain.model.PasswordResetError
import com.shelflife.feature.user.domain.model.RefreshError
import com.shelflife.feature.user.domain.model.RegisterError
import com.shelflife.feature.user.domain.model.VerifyEmailError

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
        userId: Int,
        refreshToken: String,
    )

    suspend fun logoutAllSessions(userId: Int)

    suspend fun refreshToken(oldRefreshToken: String): AppResult<AuthResponse, RefreshError>

    suspend fun verifyEmail(token: String): AppResult<Unit, VerifyEmailError>

    suspend fun resendVerificationEmail(userId: Int): AppResult<Unit, VerifyEmailError>

    suspend fun requestPasswordReset(email: String)

    suspend fun validatePasswordResetToken(token: String): AppResult<String, PasswordResetError>

    suspend fun confirmPasswordReset(
        token: String,
        newPassword: String,
    ): AppResult<Unit, PasswordResetError>
}
