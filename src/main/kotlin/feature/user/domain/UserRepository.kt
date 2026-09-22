package com.mozgobolt.feature.user.domain

import com.mozgobolt.feature.user.domain.model.PasswordResetToken
import com.mozgobolt.feature.user.domain.model.TokenValidationResult
import com.mozgobolt.feature.user.domain.model.User
import com.mozgobolt.feature.user.domain.model.UserContactInfo
import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.user.domain.model.VerificationTokenRecord
import java.time.Instant

@Suppress("TooManyFunctions", "ComplexInterface")
interface UserRepository {
    suspend fun findUser(email: String): User?

    suspend fun findUserById(userId: Int): User?

    suspend fun createUser(
        email: String,
        password: String,
        name: String,
        role: UserRole,
        phoneNumber: String? = null,
        whatsappNumber: String? = null,
        viberNumber: String? = null,
        messengerUsername: String? = null,
    ): User

    suspend fun saveRefreshToken(
        userId: Int,
        token: String,
        familyId: String,
    )

    suspend fun validateAndRevokeRefreshToken(
        userId: Int,
        token: String,
    ): TokenValidationResult

    suspend fun recordFailedLogin(
        userId: Int,
        lockUntil: Instant?,
    )

    suspend fun resetFailedLogins(userId: Int)

    suspend fun revokeTokenFamily(familyId: String)

    suspend fun revokeAllTokensForUser(userId: Int)

    suspend fun revokeSpecificRefreshToken(
        userId: Int,
        token: String,
    )

    suspend fun createVerificationToken(
        userId: Int,
        token: String,
        expiresAt: Instant,
    )

    suspend fun findVerificationToken(token: String): VerificationTokenRecord?

    suspend fun markTokenUsed(tokenId: Int): Boolean

    suspend fun markEmailVerified(userId: Int)

    suspend fun invalidateVerificationTokens(userId: Int)

    suspend fun createPasswordResetToken(
        userId: Int,
        token: String,
        expiresAt: Instant,
    )

    suspend fun findPasswordResetToken(token: String): PasswordResetToken?

    suspend fun markPasswordResetTokenUsed(
        tokenId: Int,
        usedAt: Instant,
    ): Boolean

    suspend fun invalidatePasswordResetTokens(userId: Int)

    suspend fun updatePassword(
        userId: Int,
        newPasswordHash: String,
    )

    suspend fun updateContactInfo(
        userId: Int,
        contactInfo: UserContactInfo,
    )
}
