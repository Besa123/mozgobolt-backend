package com.besa.boardShare.feature.user.service

import com.besa.boardShare.core.domain.AppResult
import com.besa.boardShare.core.domain.security.PasswordService
import com.besa.boardShare.core.domain.security.TokenManager
import com.besa.boardShare.feature.user.domain.UserRepository
import com.besa.boardShare.feature.user.domain.UserService
import com.besa.boardShare.feature.user.domain.model.AuthError
import com.besa.boardShare.feature.user.domain.model.AuthResponse

class UserServiceI(
    private val userRepository: UserRepository,
    private val passwordService: PasswordService,
    private val tokenManager: TokenManager
) : UserService {
    override suspend fun createUser(
        password: String,
        email: String,
        name: String,
    ): AppResult<AuthResponse, AuthError> {
        val user = userRepository.findUser(email)
        if (user != null) return AppResult.Error(AuthError.ALREADY_EXISTS)

        val encryptPassword = passwordService.hashPassword(password)
        val createdUser = userRepository.createUser(
            email = email,
            password = encryptPassword,
            name = name
        )

        val accessToken = tokenManager.generateAccessToken(
            userId = createdUser.id,
            email = createdUser.email
        )
        val refreshToken = tokenManager.generateRefreshToken(
            userId = createdUser.id
        )

        userRepository.saveRefreshToken(createdUser.id, refreshToken)

        return AppResult.Success(
            AuthResponse(
                accessToken = accessToken,
                refreshToken = refreshToken
            )
        )
    }

    override suspend fun signInUser(
        password: String,
        email: String
    ): AppResult<AuthResponse, AuthError> {


        // CSINÁÉLD MEG
        // REGISTER NE GENERÁLJON TOKENT
        // LOGIN GENERÁLJON EGYEDÜL
        // OLDD MEG HOGY CSAK VALAMI 200 térjen vissza a register
        // VIGYÁZZ HOGY HA VISSZATÉRSZ A TOKENNEL AKKOR A KIFELE DTO OBJECTEN LEGYEN @SERI


    }
}