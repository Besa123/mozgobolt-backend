package com.shelflife.feature.user.routing

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.user.domain.UserService
import com.shelflife.feature.user.domain.model.AuthResponse
import com.shelflife.feature.user.domain.model.LoginError
import com.shelflife.feature.user.domain.model.PasswordResetError
import com.shelflife.feature.user.domain.model.RefreshError
import com.shelflife.feature.user.domain.model.RegisterError
import com.shelflife.feature.user.domain.model.VerifyEmailError

class FakeUserService : UserService {
    data class CreateUserCall(
        val password: String,
        val email: String,
        val name: String,
    )

    var createUserResult: AppResult<Unit, RegisterError> = AppResult.Success(Unit)
    var lastCreateUserCall: CreateUserCall? = null
        private set
    var createUserCallCount: Int = 0
        private set

    var signInResult: AppResult<AuthResponse, LoginError> =
        AppResult.Success(AuthResponse(accessToken = "fake-access", refreshToken = "fake-refresh"))

    val logoutUserCalls = mutableListOf<String>()
    val logoutAllSessionsCalls = mutableListOf<Int>()

    var refreshTokenResult: AppResult<AuthResponse, RefreshError> =
        AppResult.Success(AuthResponse(accessToken = "fake-access-2", refreshToken = "fake-refresh-2"))

    var verifyEmailResult: AppResult<Unit, VerifyEmailError> = AppResult.Success(Unit)
    var lastVerifyEmailToken: String? = null
        private set

    var resendVerificationEmailResult: AppResult<Unit, VerifyEmailError> = AppResult.Success(Unit)
    val resendVerificationEmailCalls = mutableListOf<Int>()

    var requestPasswordResetResult: AppResult<Unit, PasswordResetError> = AppResult.Success(Unit)
    val requestPasswordResetCalls = mutableListOf<String>()

    var validatePasswordResetTokenResult: AppResult<String, PasswordResetError> =
        AppResult.Success("user@example.com")
    val validatePasswordResetTokenCalls = mutableListOf<String>()

    var confirmPasswordResetResult: AppResult<Unit, PasswordResetError> = AppResult.Success(Unit)

    data class ConfirmPasswordResetCall(
        val token: String,
        val newPassword: String,
    )

    var lastConfirmPasswordResetCall: ConfirmPasswordResetCall? = null
        private set

    override suspend fun createUser(
        password: String,
        email: String,
        name: String,
    ): AppResult<Unit, RegisterError> {
        createUserCallCount++
        lastCreateUserCall = CreateUserCall(password, email, name)
        return createUserResult
    }

    override suspend fun signInUser(
        password: String,
        email: String,
    ): AppResult<AuthResponse, LoginError> = signInResult

    override suspend fun logoutUser(refreshToken: String) {
        logoutUserCalls += refreshToken
    }

    override suspend fun logoutAllSessions(userId: Int) {
        logoutAllSessionsCalls += userId
    }

    override suspend fun refreshToken(oldRefreshToken: String): AppResult<AuthResponse, RefreshError> =
        refreshTokenResult

    override suspend fun verifyEmail(token: String): AppResult<Unit, VerifyEmailError> {
        lastVerifyEmailToken = token
        return verifyEmailResult
    }

    override suspend fun resendVerificationEmail(userId: Int): AppResult<Unit, VerifyEmailError> {
        resendVerificationEmailCalls += userId
        return resendVerificationEmailResult
    }

    override suspend fun requestPasswordReset(email: String): AppResult<Unit, PasswordResetError> {
        requestPasswordResetCalls += email
        return requestPasswordResetResult
    }

    override suspend fun validatePasswordResetToken(token: String): AppResult<String, PasswordResetError> {
        validatePasswordResetTokenCalls += token
        return validatePasswordResetTokenResult
    }

    override suspend fun confirmPasswordReset(
        token: String,
        newPassword: String,
    ): AppResult<Unit, PasswordResetError> {
        lastConfirmPasswordResetCall = ConfirmPasswordResetCall(token, newPassword)
        return confirmPasswordResetResult
    }
}
