/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.domain

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.user.domain.model.AuthResponse
import com.mozgobolt.feature.user.domain.model.ContactInfoError
import com.mozgobolt.feature.user.domain.model.CurrentUserProfile
import com.mozgobolt.feature.user.domain.model.DriverContactInfo
import com.mozgobolt.feature.user.domain.model.LoginError
import com.mozgobolt.feature.user.domain.model.PasswordResetError
import com.mozgobolt.feature.user.domain.model.RefreshError
import com.mozgobolt.feature.user.domain.model.RegisterError
import com.mozgobolt.feature.user.domain.model.UserContactInfo
import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.user.domain.model.VerifyEmailError

/** User-facing account, authentication, and credential-recovery use cases. */
interface UserService {
    /** Registers an account and returns a domain error when registration cannot complete. */
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

    /** Returns the driver's contact details used by vehicle-facing features. */
    suspend fun findContactInfo(userId: Int): DriverContactInfo?

    /** Returns the profile visible to the authenticated account owner. */
    suspend fun findCurrentUser(userId: Int): CurrentUserProfile?

    /** Full-replace: the caller always sends every field's current desired state (see [UserContactInfo]). */
    suspend fun updateContactInfo(
        userId: Int,
        contactInfo: UserContactInfo,
    ): AppResult<UserContactInfo, ContactInfoError>

    /** Authenticates credentials and returns a new access/refresh-token pair. */
    suspend fun signInUser(
        password: String,
        email: String,
    ): AppResult<AuthResponse, LoginError>

    /** Rotates a refresh token and returns the replacement token pair. */
    suspend fun refreshToken(oldRefreshToken: String): AppResult<AuthResponse, RefreshError>

    /** Consumes an email-verification token and verifies the associated account. */
    suspend fun verifyEmail(token: String): AppResult<Unit, VerifyEmailError>

    /** Queues another verification email for an account that can be verified. */
    suspend fun resendVerificationEmail(userId: Int): AppResult<Unit, VerifyEmailError>

    /** Queues a password-reset message when the supplied email is eligible. */
    suspend fun requestPasswordReset(email: String)

    /** Validates a password-reset token and returns its associated account identifier. */
    suspend fun validatePasswordResetToken(token: String): AppResult<String, PasswordResetError>

    /** Consumes a reset token and replaces the account password. */
    suspend fun confirmPasswordReset(
        token: String,
        newPassword: String,
    ): AppResult<Unit, PasswordResetError>
}
