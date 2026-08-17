package com.besa.boardShare.feature.user.domain

import com.besa.boardShare.feature.user.domain.model.TokenValidationResult
import com.besa.boardShare.feature.user.domain.model.User

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

    suspend fun revokeTokenFamily(familyId: String)

    suspend fun revokeAllTokensForUser(userId: Int)

    suspend fun revokeSpecificRefreshToken(token: String)
}