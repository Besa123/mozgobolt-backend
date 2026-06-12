package com.besa.boardShare.feature.user.domain

import com.besa.boardShare.core.domain.AppResult
import com.besa.boardShare.feature.user.domain.model.AuthResponse
import com.besa.boardShare.feature.user.domain.model.LoginError
import com.besa.boardShare.feature.user.domain.model.RegisterError

interface UserService {
    suspend fun createUser(
        password: String,
        email: String,
        name: String,
    ): AppResult<Unit, RegisterError>

    suspend fun signInUser(
        password: String,
        email: String,
    ): AppResult<AuthResponse, LoginError>
}