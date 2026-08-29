package com.shelflife.feature.user.service

import com.shelflife.core.database.TransactionalRunner
import com.shelflife.core.domain.AppResult
import com.shelflife.core.domain.email.EmailService
import com.shelflife.core.domain.security.PasswordService
import com.shelflife.core.domain.security.SecureTokenGenerator
import com.shelflife.core.domain.security.TokenManager
import com.shelflife.core.domain.validation.EmailValidator
import com.shelflife.core.domain.validation.PasswordValidator
import com.shelflife.core.modules.AppConfig
import com.shelflife.core.utility.functions.runSuspendCatching
import com.shelflife.feature.user.domain.UserRepository
import com.shelflife.feature.user.domain.UserService
import com.shelflife.feature.user.domain.model.AuthResponse
import com.shelflife.feature.user.domain.model.LockoutPolicy
import com.shelflife.feature.user.domain.model.LoginError
import com.shelflife.feature.user.domain.model.PasswordResetError
import com.shelflife.feature.user.domain.model.RefreshError
import com.shelflife.feature.user.domain.model.RegisterError
import com.shelflife.feature.user.domain.model.TokenValidationResult
import com.shelflife.feature.user.domain.model.VerifyEmailError
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

private val logger = KotlinLogging.logger {}

class UserServiceI(
    private val userRepository: UserRepository,
    private val passwordService: PasswordService,
    private val tokenManager: TokenManager,
    private val passwordValidator: PasswordValidator,
    private val emailValidator: EmailValidator,
    private val emailService: EmailService,
    private val appConfig: AppConfig,
    private val tx: TransactionalRunner,
) : UserService {
    private val dummyPasswordHash: String by lazy {
        passwordService.hashPassword("no-such-account-timing-safety-placeholder")
    }

    @Suppress("ReturnCount")
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

        val result =
            tx.transactional {
                val existing = userRepository.findUser(normalizedEmail)
                if (existing != null) return@transactional null

                val newUser =
                    userRepository.createUser(
                        email = normalizedEmail,
                        password = hashedPassword,
                        name = name,
                    )

                val verificationToken = SecureTokenGenerator.generate()
                val expiresAt = Instant.now().plus(appConfig.email.verificationTokenExpirationHours, ChronoUnit.HOURS)
                userRepository.createVerificationToken(
                    newUser.id,
                    tokenManager.hashTokenForStorage(verificationToken),
                    expiresAt,
                )

                newUser to verificationToken
            } ?: return AppResult.Error(RegisterError.ALREADY_EXISTS)

        runSuspendCatching { emailService.sendVerificationEmail(result.first.email, result.second) }
            .onFailure { logger.error(it) { "Failed to send verification email to ${result.first.email}" } }

        return AppResult.Success(Unit)
    }

    override suspend fun signInUser(
        password: String,
        email: String,
    ): AppResult<AuthResponse, LoginError> {
        val normalizedEmail = emailValidator.normalize(email)

        return tx.transactional {
            val user =
                userRepository.findUser(normalizedEmail) ?: run {
                    passwordService.verifyPassword(password = password, hash = dummyPasswordHash)
                    return@transactional AppResult.Error(LoginError.INVALID_CREDENTIALS)
                }

            if (user.isLocked) {
                logger.warn { "Login attempt on locked account: ${user.id}" }
                return@transactional AppResult.Error(LoginError.ACCOUNT_LOCKED)
            }

            val isPasswordValid =
                passwordService.verifyPassword(
                    password = password,
                    hash = user.passwordHash,
                )

            if (!isPasswordValid) {
                val newAttemptCount = user.failedLoginAttempts + 1
                val lockUntil = LockoutPolicy.calculateLockUntil(newAttemptCount)
                userRepository.recordFailedLogin(user.id, lockUntil)

                if (lockUntil != null) {
                    logger.warn { "Account ${user.id} locked until $lockUntil after $newAttemptCount failed attempts" }
                }

                return@transactional AppResult.Error(LoginError.INVALID_CREDENTIALS)
            }

            if (user.failedLoginAttempts > 0) {
                userRepository.resetFailedLogins(user.id)
            }

            val accessToken = tokenManager.generateAccessToken(userId = user.id)
            val refreshToken = tokenManager.generateRefreshToken(userId = user.id)
            val familyId = UUID.randomUUID().toString()

            userRepository.saveRefreshToken(user.id, tokenManager.hashTokenForStorage(refreshToken), familyId)

            AppResult.Success(
                AuthResponse(accessToken = accessToken, refreshToken = refreshToken),
            )
        }
    }

    override suspend fun logoutUser(refreshToken: String) =
        tx.transactional {
            userRepository.revokeSpecificRefreshToken(token = tokenManager.hashTokenForStorage(refreshToken))
        }

    override suspend fun logoutAllSessions(userId: Int) =
        tx.transactional {
            userRepository.revokeAllTokensForUser(userId)
        }

    override suspend fun refreshToken(oldRefreshToken: String): AppResult<AuthResponse, RefreshError> {
        val userId =
            tokenManager.verifyAndGetUserIdFromRefreshToken(oldRefreshToken)
                ?: return AppResult.Error(RefreshError.INVALID_CREDENTIALS)

        val hashedOldToken = tokenManager.hashTokenForStorage(oldRefreshToken)

        return tx.transactional {
            val user =
                userRepository.findUserById(userId)
                    ?: return@transactional AppResult.Error(RefreshError.INVALID_CREDENTIALS)

            when (val result = userRepository.validateAndRevokeRefreshToken(userId, hashedOldToken)) {
                is TokenValidationResult.Valid -> {
                    val accessToken = tokenManager.generateAccessToken(userId = user.id)
                    val refreshToken = tokenManager.generateRefreshToken(userId = user.id)

                    userRepository.saveRefreshToken(
                        user.id,
                        tokenManager.hashTokenForStorage(refreshToken),
                        result.familyId,
                    )

                    AppResult.Success(
                        AuthResponse(accessToken = accessToken, refreshToken = refreshToken),
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

    override suspend fun verifyEmail(token: String): AppResult<Unit, VerifyEmailError> {
        return tx.transactional {
            val record =
                userRepository.findVerificationToken(tokenManager.hashTokenForStorage(token))
                    ?: return@transactional AppResult.Error(VerifyEmailError.INVALID_TOKEN)

            if (record.used) {
                return@transactional AppResult.Error(VerifyEmailError.INVALID_TOKEN)
            }

            if (record.expiresAt.isBefore(Instant.now())) {
                return@transactional AppResult.Error(VerifyEmailError.EXPIRED_TOKEN)
            }

            val user = userRepository.findUserById(record.userId)
            if (user?.isEmailVerified == true) {
                return@transactional AppResult.Error(VerifyEmailError.ALREADY_VERIFIED)
            }

            userRepository.markTokenUsed(record.id)
            userRepository.markEmailVerified(record.userId)
            userRepository.invalidateVerificationTokens(record.userId)

            logger.info { "Email verified for user ${record.userId}" }
            AppResult.Success(Unit)
        }
    }

    override suspend fun resendVerificationEmail(userId: Int): AppResult<Unit, VerifyEmailError> {
        val user =
            tx.transactional { userRepository.findUserById(userId) }
                ?: return AppResult.Error(VerifyEmailError.INVALID_TOKEN)

        if (user.isEmailVerified) {
            return AppResult.Error(VerifyEmailError.ALREADY_VERIFIED)
        }

        val verificationToken = SecureTokenGenerator.generate()
        val expiresAt = Instant.now().plus(appConfig.email.verificationTokenExpirationHours, ChronoUnit.HOURS)

        tx.transactional {
            userRepository.invalidateVerificationTokens(userId)
            userRepository.createVerificationToken(
                userId,
                tokenManager.hashTokenForStorage(verificationToken),
                expiresAt,
            )
        }

        runSuspendCatching { emailService.sendVerificationEmail(user.email, verificationToken) }
            .onFailure { logger.error(it) { "Failed to resend verification email to ${user.email}" } }

        return AppResult.Success(Unit)
    }

    override suspend fun requestPasswordReset(email: String): AppResult<Unit, PasswordResetError> {
        val normalizedEmail = emailValidator.normalize(email)
        val user = tx.transactional { userRepository.findUser(normalizedEmail) }

        if (user == null) {
            return AppResult.Success(Unit)
        }

        if (user.isLocked) {
            logger.warn { "Password reset requested for locked account: ${user.id}" }
            return AppResult.Success(Unit)
        }

        val resetToken = SecureTokenGenerator.generate()
        val expiresAt = Instant.now().plus(appConfig.email.resetTokenExpirationMinutes, ChronoUnit.MINUTES)

        tx.transactional {
            userRepository.invalidatePasswordResetTokens(user.id)
            userRepository.createPasswordResetToken(user.id, tokenManager.hashTokenForStorage(resetToken), expiresAt)
        }

        runSuspendCatching { emailService.sendPasswordResetEmail(user.email, resetToken) }
            .onFailure { logger.error(it) { "Failed to send password reset email to ${user.email}" } }

        return AppResult.Success(Unit)
    }

    override suspend fun validatePasswordResetToken(token: String): AppResult<String, PasswordResetError> {
        val record =
            tx.transactional { userRepository.findPasswordResetToken(tokenManager.hashTokenForStorage(token)) }
                ?: return AppResult.Error(PasswordResetError.INVALID_TOKEN)

        if (record.used) {
            return AppResult.Error(PasswordResetError.INVALID_TOKEN)
        }

        if (record.expiresAt.isBefore(Instant.now())) {
            return AppResult.Error(PasswordResetError.EXPIRED_TOKEN)
        }

        val user =
            tx.transactional { userRepository.findUserById(record.userId) }
                ?: return AppResult.Error(PasswordResetError.USER_NOT_FOUND)

        return AppResult.Success(user.email)
    }

    @Suppress("ReturnCount")
    override suspend fun confirmPasswordReset(
        token: String,
        newPassword: String,
    ): AppResult<Unit, PasswordResetError> {
        val record =
            tx.transactional { userRepository.findPasswordResetToken(tokenManager.hashTokenForStorage(token)) }
                ?: return AppResult.Error(PasswordResetError.INVALID_TOKEN)

        if (record.used) {
            return AppResult.Error(PasswordResetError.INVALID_TOKEN)
        }

        if (record.expiresAt.isBefore(Instant.now())) {
            return AppResult.Error(PasswordResetError.EXPIRED_TOKEN)
        }

        val isPasswordValid = passwordValidator.isValid(newPassword)
        if (!isPasswordValid) return AppResult.Error(PasswordResetError.WEAK_PASSWORD)

        return tx.transactional {
            val user =
                userRepository.findUserById(record.userId)
                    ?: return@transactional AppResult.Error(PasswordResetError.USER_NOT_FOUND)

            if (user.isLocked) {
                logger.warn { "Password reset attempt on locked account: ${user.id}" }
                return@transactional AppResult.Error(PasswordResetError.ACCOUNT_LOCKED)
            }

            val isSameAsOld = passwordService.verifyPassword(password = newPassword, hash = user.passwordHash)
            if (isSameAsOld) {
                return@transactional AppResult.Error(PasswordResetError.SAME_AS_OLD)
            }

            val claimed = userRepository.markPasswordResetTokenUsed(record.id, Instant.now())
            if (!claimed) {
                return@transactional AppResult.Error(PasswordResetError.INVALID_TOKEN)
            }

            val newHashedPassword = passwordService.hashPassword(newPassword)
            userRepository.updatePassword(user.id, newHashedPassword)
            userRepository.invalidatePasswordResetTokens(user.id)
            userRepository.revokeAllTokensForUser(user.id)

            logger.info { "Password reset completed for user ${user.id}" }
            AppResult.Success(Unit)
        }
    }
}
