package com.mozgobolt.feature.user.routing

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.user.domain.UserService
import com.mozgobolt.feature.user.domain.model.AuthResponse
import com.mozgobolt.feature.user.domain.model.ContactInfoError
import com.mozgobolt.feature.user.domain.model.DriverContactInfo
import com.mozgobolt.feature.user.domain.model.LoginError
import com.mozgobolt.feature.user.domain.model.PasswordResetError
import com.mozgobolt.feature.user.domain.model.RefreshError
import com.mozgobolt.feature.user.domain.model.RegisterError
import com.mozgobolt.feature.user.domain.model.UserContactInfo
import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.user.domain.model.VerifyEmailError

class FakeUserService : UserService {
    data class CreateUserCall(
        val password: String,
        val email: String,
        val name: String,
        val role: UserRole,
        val phoneNumber: String?,
        val whatsappNumber: String?,
        val viberNumber: String?,
        val messengerUsername: String?,
    )

    var createUserResult: AppResult<Unit, RegisterError> = AppResult.Success(Unit)
    var lastCreateUserCall: CreateUserCall? = null
        private set
    var createUserCallCount: Int = 0
        private set

    var findContactInfoResult: DriverContactInfo? = null
    val findContactInfoCalls = mutableListOf<Int>()

    data class UpdateContactInfoCall(
        val userId: Int,
        val contactInfo: UserContactInfo,
    )

    var updateContactInfoResult: AppResult<UserContactInfo, ContactInfoError> =
        AppResult.Success(
            UserContactInfo(
                phoneNumber = null,
                phoneNumberVisible = true,
                whatsappNumber = null,
                whatsappVisible = true,
                viberNumber = null,
                viberVisible = true,
                messengerUsername = null,
                messengerVisible = true,
            ),
        )
    var lastUpdateContactInfoCall: UpdateContactInfoCall? = null
        private set

    var signInResult: AppResult<AuthResponse, LoginError> =
        AppResult.Success(AuthResponse(accessToken = "fake-access", refreshToken = "fake-refresh"))

    data class LogoutCall(
        val userId: Int,
        val refreshToken: String,
    )

    val logoutUserCalls = mutableListOf<LogoutCall>()
    val logoutAllSessionsCalls = mutableListOf<Int>()

    var refreshTokenResult: AppResult<AuthResponse, RefreshError> =
        AppResult.Success(AuthResponse(accessToken = "fake-access-2", refreshToken = "fake-refresh-2"))

    var verifyEmailResult: AppResult<Unit, VerifyEmailError> = AppResult.Success(Unit)
    var lastVerifyEmailToken: String? = null
        private set

    var resendVerificationEmailResult: AppResult<Unit, VerifyEmailError> = AppResult.Success(Unit)
    val resendVerificationEmailCalls = mutableListOf<Int>()

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
        role: UserRole,
        phoneNumber: String?,
        whatsappNumber: String?,
        viberNumber: String?,
        messengerUsername: String?,
    ): AppResult<Unit, RegisterError> {
        createUserCallCount++
        lastCreateUserCall =
            CreateUserCall(password, email, name, role, phoneNumber, whatsappNumber, viberNumber, messengerUsername)
        return createUserResult
    }

    override suspend fun findContactInfo(userId: Int): DriverContactInfo? {
        findContactInfoCalls += userId
        return findContactInfoResult
    }

    override suspend fun updateContactInfo(
        userId: Int,
        contactInfo: UserContactInfo,
    ): AppResult<UserContactInfo, ContactInfoError> {
        lastUpdateContactInfoCall = UpdateContactInfoCall(userId, contactInfo)
        return updateContactInfoResult
    }

    override suspend fun signInUser(
        password: String,
        email: String,
    ): AppResult<AuthResponse, LoginError> = signInResult

    override suspend fun logoutUser(
        userId: Int,
        refreshToken: String,
    ) {
        logoutUserCalls += LogoutCall(userId, refreshToken)
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

    override suspend fun requestPasswordReset(email: String) {
        requestPasswordResetCalls += email
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
