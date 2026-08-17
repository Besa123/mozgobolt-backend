package com.besa.boardShare.feature.user.service

import com.besa.boardShare.core.database.TransactionalRunner
import com.besa.boardShare.core.domain.AppResult
import com.besa.boardShare.core.domain.security.PasswordService
import com.besa.boardShare.core.domain.security.TokenManager
import com.besa.boardShare.core.domain.validation.EmailValidator
import com.besa.boardShare.core.domain.validation.PasswordValidator
import com.besa.boardShare.feature.user.domain.UserRepository
import com.besa.boardShare.feature.user.domain.UserService
import com.besa.boardShare.feature.user.domain.model.*
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.*

private val logger = KotlinLogging.logger {}

class UserServiceI(
    private val userRepository: UserRepository,
    private val passwordService: PasswordService,
    private val tokenManager: TokenManager,
    private val passwordValidator: PasswordValidator,
    private val emailValidator: EmailValidator,
    private val tx: TransactionalRunner,
) : UserService {

    override suspend fun createUser(
        password: String,
        email: String,
        name: String,
    ): AppResult<Unit, RegisterError> {
        val normalizedEmail = emailValidator.normalize(email)

        if (!emailValidator.isValid(normalizedEmail)) {
            return AppResult.Error(RegisterError.INVALID_EMAIL)
        }

        val isPasswordValid = passwordValidator.isValid(password)
        if (!isPasswordValid) return AppResult.Error(RegisterError.WEAK_PASSWORD)

        val hashedPassword = passwordService.hashPassword(password)

        return tx.transactional {
            val existing = userRepository.findUser(normalizedEmail)
            if (existing != null) return@transactional AppResult.Error(RegisterError.ALREADY_EXISTS)

            userRepository.createUser(
                email = normalizedEmail,
                password = hashedPassword,
                name = name
            )
            AppResult.Success(Unit)
        }
    }

    override suspend fun signInUser(
        password: String,
        email: String
    ): AppResult<AuthResponse, LoginError> {
        val normalizedEmail = emailValidator.normalize(email)

        return tx.transactional {
            val user = userRepository.findUser(normalizedEmail)
                ?: return@transactional AppResult.Error(LoginError.INVALID_CREDENTIALS)

            val isPasswordValid = passwordService.verifyPassword(
                password = password,
                hash = user.passwordHash
            )

            if (!isPasswordValid) return@transactional AppResult.Error(LoginError.INVALID_CREDENTIALS)

            val accessToken = tokenManager.generateAccessToken(userId = user.id)
            val refreshToken = tokenManager.generateRefreshToken(userId = user.id)
            val familyId = UUID.randomUUID().toString()

            userRepository.saveRefreshToken(user.id, tokenManager.hashTokenForStorage(refreshToken), familyId)

            AppResult.Success(
                AuthResponse(accessToken = accessToken, refreshToken = refreshToken)
            )
        }
    }

    override suspend fun logoutUser(refreshToken: String) = tx.transactional {
        userRepository.revokeSpecificRefreshToken(token = tokenManager.hashTokenForStorage(refreshToken))
    }

    override suspend fun logoutAllSessions(userId: Int) = tx.transactional {
        userRepository.revokeAllTokensForUser(userId)
    }

    override suspend fun refreshToken(oldRefreshToken: String): AppResult<AuthResponse, RefreshError> {
        val userId = tokenManager.verifyAndGetUserIdFromRefreshToken(oldRefreshToken)
            ?: return AppResult.Error(RefreshError.INVALID_CREDENTIALS)

        val hashedOldToken = tokenManager.hashTokenForStorage(oldRefreshToken)

        return tx.transactional {
            val user = userRepository.findUserById(userId)
                ?: return@transactional AppResult.Error(RefreshError.INVALID_CREDENTIALS)

            when (val result = userRepository.validateAndRevokeRefreshToken(userId, hashedOldToken)) {
                is TokenValidationResult.Valid -> {
                    val accessToken = tokenManager.generateAccessToken(userId = user.id)
                    val refreshToken = tokenManager.generateRefreshToken(userId = user.id)

                    userRepository.saveRefreshToken(
                        user.id,
                        tokenManager.hashTokenForStorage(refreshToken),
                        result.familyId
                    )

                    AppResult.Success(
                        AuthResponse(accessToken = accessToken, refreshToken = refreshToken)
                    )
                }

                is TokenValidationResult.AlreadyRevoked -> {
                    logger.warn {
                        "Refresh token reuse detected for user $userId, family ${result.familyId}. " +
                                "Revoking entire token family — possible token theft."
                    }
                    userRepository.revokeTokenFamily(result.familyId)
                    AppResult.Error(RefreshError.TOKEN_REUSE_DETECTED)
                }

                is TokenValidationResult.NotFound -> {
                    AppResult.Error(RefreshError.INVALID_CREDENTIALS)
                }
            }
        }
    }
}