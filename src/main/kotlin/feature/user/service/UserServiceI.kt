package com.besa.boardShare.feature.user.service

import com.besa.boardShare.core.domain.AppResult
import com.besa.boardShare.core.domain.security.PasswordService
import com.besa.boardShare.core.domain.security.TokenManager
import com.besa.boardShare.core.domain.validation.PasswordValidator
import com.besa.boardShare.feature.user.domain.UserRepository
import com.besa.boardShare.feature.user.domain.UserService
import com.besa.boardShare.feature.user.domain.model.AuthResponse
import com.besa.boardShare.feature.user.domain.model.LoginError
import com.besa.boardShare.feature.user.domain.model.RefreshError
import com.besa.boardShare.feature.user.domain.model.RegisterError

class UserServiceI(
    private val userRepository: UserRepository,
    private val passwordService: PasswordService,
    private val tokenManager: TokenManager,
    private val passwordValidator: PasswordValidator,
) : UserService {
    override suspend fun createUser(
        password: String,
        email: String,
        name: String,
    ): AppResult<Unit, RegisterError> {
        val user = userRepository.findUser(email)
        if (user != null) return AppResult.Error(RegisterError.ALREADY_EXISTS)

        val isPasswordValid = passwordValidator.isValid(password)
        if (!isPasswordValid) return AppResult.Error(RegisterError.WEAK_PASSWORD)

        val hashedPassword = passwordService.hashPassword(password)
        userRepository.createUser(
            email = email,
            password = hashedPassword,
            name = name
        )

        return AppResult.Success(Unit)
    }

    override suspend fun signInUser(
        password: String,
        email: String
    ): AppResult<AuthResponse, LoginError> {
        val user = userRepository.findUser(email)
            ?: return AppResult.Error(LoginError.USER_DOES_NOT_EXIST)

        val hashPassword = user.passwordHash
        val isPasswordValid = passwordService.verifyPassword(
            password = password,
            hash = hashPassword
        )

        if (!isPasswordValid) return AppResult.Error(LoginError.INVALID_CREDENTIALS)


        val accessToken = tokenManager.generateAccessToken(
            userId = user.id,
            email = user.email
        )
        val refreshToken = tokenManager.generateRefreshToken(
            userId = user.id
        )

        userRepository.saveRefreshToken(user.id, refreshToken)

        return AppResult.Success(
            AuthResponse(
                accessToken = accessToken,
                refreshToken = refreshToken
            )
        )
    }

    override suspend fun logoutUser(refreshToken: String) {
        userRepository.revokeSpecificRefreshToken(token = refreshToken)
    }

    override suspend fun refreshToken(oldRefreshToken: String): AppResult<AuthResponse, RefreshError> {
        val userId = tokenManager.verifyAndGetUserIdFromRefreshToken(oldRefreshToken)
            ?: return AppResult.Error(RefreshError.INVALID_CREDENTIALS)

        val user = userRepository.findUserById(userId)
            ?: return AppResult.Error(RefreshError.INVALID_CREDENTIALS)

        val isValidToken = userRepository.validateAndRevokeRefreshToken(
            userId = userId,
            token = oldRefreshToken
        )

        if (!isValidToken) {
            userRepository.revokeAllTokensForUser(userId)
            return AppResult.Error(RefreshError.INVALID_CREDENTIALS)
        }

        val accessToken = tokenManager.generateAccessToken(
            userId = user.id,
            email = user.email
        )
        val refreshToken = tokenManager.generateRefreshToken(
            userId = user.id
        )

        userRepository.saveRefreshToken(user.id, refreshToken)

        return AppResult.Success(
            AuthResponse(
                accessToken = accessToken,
                refreshToken = refreshToken
            )
        )
    }
}