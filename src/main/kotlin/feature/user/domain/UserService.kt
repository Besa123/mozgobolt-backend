package com.mozgobolt.feature.user.domain

import com.mozgobolt.core.domain.AppResult
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

interface UserService {
    suspend fun createUser(
        password: String,
        email: String,
        name: String,
        role: UserRole,
        phoneNumber: String? = null,
        whatsappNumber: String? = null,
        viberNumber: String? = null,
        messengerUsername: String? = null,
    ): AppResult<Unit, RegisterError>

    suspend fun findContactInfo(userId: Int): DriverContactInfo?

    /** Full-replace: the caller always sends every field's current desired state (see [UserContactInfo]). */
    suspend fun updateContactInfo(
        userId: Int,
        contactInfo: UserContactInfo,
    ): AppResult<UserContactInfo, ContactInfoError>

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
