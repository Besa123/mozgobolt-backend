package com.besa.boardShare.feature.user.domain

import com.besa.boardShare.feature.user.domain.model.User

interface UserRepository {
    suspend fun findUser(
        email: String,
    ): User?

    suspend fun createUser(
        email: String,
        password: String,
        name: String,
    ): User


    suspend fun saveRefreshToken(
        userId: Int,
        token: String
    )
}