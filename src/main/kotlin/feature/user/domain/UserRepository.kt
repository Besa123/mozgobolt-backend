package com.besa.boardShare.feature.user.domain

import com.besa.boardShare.feature.user.domain.model.TokenValidationResult
import com.besa.boardShare.feature.user.domain.model.User
import com.besa.boardShare.feature.user.domain.model.VerificationTokenRecord
import java.time.Instant

interface UserRepository {
    suspend fun findUser(
        email: String,
    ): User?

    suspend fun findUserById(
        userId: Int
    ): User?

    suspend fun createUser(
        email: String,
        password: String,
        name: String,
    ): User

    suspend fun saveRefreshToken(
        userId: Int,
        token: String,
        familyId: String,
    )

    suspend fun validateAndRevokeRefreshToken(
        userId: Int,
        token: String
    ): TokenValidationResult

    suspend fun recordFailedLogin(userId: Int, lockUntil: Instant?)

    suspend fun resetFailedLogins(userId: Int)

    suspend fun revokeTokenFamily(familyId: String)

    suspend fun revokeAllTokensForUser(userId: Int)

    suspend fun revokeSpecificRefreshToken(token: String)

    suspend fun createVerificationToken(userId: Int, token: String, expiresAt: Instant)

    suspend fun findVerificationToken(token: String): VerificationTokenRecord?

    suspend fun markTokenUsed(tokenId: Int)

    suspend fun markEmailVerified(userId: Int)

    suspend fun invalidateVerificationTokens(userId: Int)
}