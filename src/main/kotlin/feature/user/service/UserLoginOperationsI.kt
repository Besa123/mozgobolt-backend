/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.security.PasswordService
import com.mozgobolt.core.domain.security.TokenManager
import com.mozgobolt.core.domain.validation.EmailValidator
import com.mozgobolt.feature.user.domain.UserRepository
import com.mozgobolt.feature.user.domain.model.AuthResponse
import com.mozgobolt.feature.user.domain.model.LockoutPolicy
import com.mozgobolt.feature.user.domain.model.LoginError
import com.mozgobolt.feature.user.domain.model.User
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

private val logger = KotlinLogging.logger {}

internal class UserLoginOperationsI(
    private val userRepository: UserRepository,
    private val passwordService: PasswordService,
    private val tokenManager: TokenManager,
    private val emailValidator: EmailValidator,
    private val tx: TransactionalRunner,
) {
    private val dummyPasswordHashMutex = Mutex()
    private var cachedDummyPasswordHash: String? = null

    suspend fun signInUser(
        password: String,
        email: String,
    ): AppResult<AuthResponse, LoginError> {
        val normalizedEmail = emailValidator.normalize(email)
        val user =
            tx.transactional { userRepository.findUser(normalizedEmail) }
                ?: return rejectUnknownUser(password)

        if (user.isLocked) {
            logger.warn { "Login attempt on locked account: ${user.id}" }
            return AppResult.Error(LoginError.ACCOUNT_LOCKED)
        }

        return if (passwordService.verifyPassword(password = password, hash = user.passwordHash)) {
            completeSuccessfulSignIn(user)
        } else {
            recordFailedPassword(user)
        }
    }

    private suspend fun rejectUnknownUser(password: String): AppResult<AuthResponse, LoginError> {
        passwordService.verifyPassword(password = password, hash = dummyPasswordHash())
        return AppResult.Error(LoginError.INVALID_CREDENTIALS)
    }

    private suspend fun dummyPasswordHash(): String =
        dummyPasswordHashMutex.withLock {
            cachedDummyPasswordHash
                ?: passwordService
                    .hashPassword("no-such-account-timing-safety-placeholder")
                    .also { cachedDummyPasswordHash = it }
        }

    private suspend fun completeSuccessfulSignIn(user: User): AppResult<AuthResponse, LoginError> =
        tx.transactional {
            val currentUser =
                userRepository.findUserByIdForUpdate(user.id)
                    ?: return@transactional AppResult.Error(LoginError.INVALID_CREDENTIALS)
            if (currentUser.isLocked) {
                logger.warn { "Login attempt on locked account: ${currentUser.id}" }
                return@transactional AppResult.Error(LoginError.ACCOUNT_LOCKED)
            }
            if (currentUser.passwordHash != user.passwordHash) {
                return@transactional AppResult.Error(LoginError.INVALID_CREDENTIALS)
            }

            if (currentUser.failedLoginAttempts > 0) {
                userRepository.resetFailedLogins(currentUser.id)
            }

            val familyId = UUID.randomUUID().toString()
            val refreshToken = tokenManager.generateRefreshToken(userId = currentUser.id)
            userRepository.saveRefreshToken(
                currentUser.id,
                tokenManager.hashTokenForStorage(refreshToken),
                familyId,
            )
            val accessToken =
                tokenManager.generateAccessToken(
                    userId = currentUser.id,
                    role = currentUser.role.name,
                    familyId = familyId,
                )

            AppResult.Success(AuthResponse(accessToken = accessToken, refreshToken = refreshToken))
        }

    private suspend fun recordFailedPassword(user: User): AppResult<AuthResponse, LoginError> =
        tx.transactional {
            val currentUser =
                userRepository.findUserByIdForUpdate(user.id)
                    ?: return@transactional AppResult.Error(LoginError.INVALID_CREDENTIALS)
            if (currentUser.isLocked) {
                logger.warn { "Login attempt on locked account: ${currentUser.id}" }
                return@transactional AppResult.Error(LoginError.ACCOUNT_LOCKED)
            }
            if (currentUser.passwordHash != user.passwordHash) {
                return@transactional AppResult.Error(LoginError.INVALID_CREDENTIALS)
            }

            val newAttemptCount = currentUser.failedLoginAttempts + 1
            val lockUntil = LockoutPolicy.calculateLockUntil(newAttemptCount)
            userRepository.recordFailedLogin(currentUser.id, lockUntil)

            if (lockUntil != null) {
                logger.warn {
                    "Account ${currentUser.id} locked until $lockUntil after $newAttemptCount failed attempts"
                }
            }

            AppResult.Error(LoginError.INVALID_CREDENTIALS)
        }
}
