package com.besa.boardShare.feature.user.domain

import com.besa.boardShare.core.domain.AppResult
import com.besa.boardShare.feature.user.domain.model.AuthError
import com.besa.boardShare.feature.user.domain.model.AuthResponse

interface UserService {
    suspend fun createUser(
        password: String,
        email: String,
        name: String,
    ): AppResult<AuthResponse, AuthError>

    suspend fun signInUser(
        password: String,
        email: String,
    ): AppResult<AuthResponse, AuthError>
}