package com.shelflife.feature.user.routing

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.user.domain.UserService
import com.shelflife.feature.user.domain.model.AuthResponse
import com.shelflife.feature.user.domain.model.LoginError
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
}
