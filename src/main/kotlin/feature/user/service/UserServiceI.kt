package com.mozgobolt.feature.user.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.email.EmailService
import com.mozgobolt.core.domain.security.PasswordService
import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.core.domain.security.TokenManager
import com.mozgobolt.core.domain.validation.EmailValidator
import com.mozgobolt.core.domain.validation.PasswordValidator
import com.mozgobolt.core.domain.validation.normalizeInternationalPhoneNumber
import com.mozgobolt.core.domain.validation.normalizePhoneNumber
import com.mozgobolt.core.domain.validation.validateMessengerUsername
import com.mozgobolt.core.modules.AppConfig
import com.mozgobolt.core.utility.functions.runSuspendCatching
import com.mozgobolt.feature.user.domain.UserRepository
import com.mozgobolt.feature.user.domain.UserService
import com.mozgobolt.feature.user.domain.model.AuthResponse
import com.mozgobolt.feature.user.domain.model.ContactInfoError
import com.mozgobolt.feature.user.domain.model.DriverContactInfo
import com.mozgobolt.feature.user.domain.model.LockoutPolicy
import com.mozgobolt.feature.user.domain.model.LoginError
import com.mozgobolt.feature.user.domain.model.PasswordResetError
import com.mozgobolt.feature.user.domain.model.RefreshError
import com.mozgobolt.feature.user.domain.model.RegisterError
import com.mozgobolt.feature.user.domain.model.TokenValidationResult
import com.mozgobolt.feature.user.domain.model.UserContactInfo
import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.user.domain.model.VerifyEmailError
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
        role: UserRole,
        phoneNumber: String?,
        whatsappNumber: String?,
        viberNumber: String?,
        messengerUsername: String?,
    ): AppResult<Unit, RegisterError> {
        val normalizedEmail = emailValidator.normalize(email)

        if (!emailValidator.isValid(normalizedEmail)) {
            return AppResult.Error(RegisterError.INVALID_EMAIL)
        }

        val isPasswordValid = passwordValidator.isValid(password)
        if (!isPasswordValid) return AppResult.Error(RegisterError.WEAK_PASSWORD)

        // Re-validated here, not just at the request DTO, so a caller that skips DTO validation
        // (e.g. a future admin-created-account path) can't persist a value libphonenumber/the
        // username pattern would reject — the same defense-in-depth already applied to email above.
        val normalizedPhoneNumber =
            phoneNumber?.let {
                normalizePhoneNumber(it) ?: return AppResult.Error(RegisterError.INVALID_PHONE_NUMBER)
            }
        val normalizedWhatsappNumber =
            whatsappNumber?.let {
                normalizeInternationalPhoneNumber(it) ?: return AppResult.Error(RegisterError.INVALID_PHONE_NUMBER)
            }
        val normalizedViberNumber =
            viberNumber?.let {
                normalizeInternationalPhoneNumber(it) ?: return AppResult.Error(RegisterError.INVALID_PHONE_NUMBER)
            }
        if (validateMessengerUsername(messengerUsername).isNotEmpty()) {
            return AppResult.Error(RegisterError.INVALID_MESSENGER_USERNAME)
        }

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
                        role = role,
                        phoneNumber = normalizedPhoneNumber,
                        whatsappNumber = normalizedWhatsappNumber,
                        viberNumber = normalizedViberNumber,
                        messengerUsername = messengerUsername,
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
            .onFailure { logger.error(it) { "Failed to send verification email to user ${result.first.id}" } }

        return AppResult.Success(Unit)
    }

    override suspend fun findContactInfo(userId: Int): DriverContactInfo? =
        tx.transactional {
            userRepository.findUserById(userId)?.let { user ->
                DriverContactInfo(
                    phoneNumber = user.phoneNumber.takeIf { user.phoneNumberVisible },
                    whatsappNumber = user.whatsappNumber.takeIf { user.whatsappVisible },
                    viberNumber = user.viberNumber.takeIf { user.viberVisible },
                    messengerUsername = user.messengerUsername.takeIf { user.messengerVisible },
                )
            }
        }

    @Suppress("ReturnCount")
    override suspend fun updateContactInfo(
        userId: Int,
        contactInfo: UserContactInfo,
    ): AppResult<UserContactInfo, ContactInfoError> {
        val normalizedPhone =
            contactInfo.phoneNumber?.let {
                normalizePhoneNumber(it) ?: return AppResult.Error(ContactInfoError.INVALID_PHONE_NUMBER)
            }
        val normalizedWhatsapp =
            contactInfo.whatsappNumber?.let {
                normalizeInternationalPhoneNumber(it)
                    ?: return AppResult.Error(ContactInfoError.INVALID_WHATSAPP_NUMBER)
            }
        val normalizedViber =
            contactInfo.viberNumber?.let {
                normalizeInternationalPhoneNumber(it) ?: return AppResult.Error(ContactInfoError.INVALID_VIBER_NUMBER)
            }
        if (validateMessengerUsername(contactInfo.messengerUsername).isNotEmpty()) {
            return AppResult.Error(ContactInfoError.INVALID_MESSENGER_USERNAME)
        }

        val normalized =
            contactInfo.copy(
                phoneNumber = normalizedPhone,
                whatsappNumber = normalizedWhatsapp,
                viberNumber = normalizedViber,
            )
        tx.transactional { userRepository.updateContactInfo(userId, normalized) }

        return AppResult.Success(normalized)
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

            val accessToken = tokenManager.generateAccessToken(userId = user.id, role = user.role.name)
            val refreshToken = tokenManager.generateRefreshToken(userId = user.id)
            val familyId = UUID.randomUUID().toString()

            userRepository.saveRefreshToken(user.id, tokenManager.hashTokenForStorage(refreshToken), familyId)

            AppResult.Success(
                AuthResponse(accessToken = accessToken, refreshToken = refreshToken),
            )
        }
    }

    override suspend fun logoutUser(
        userId: Int,
        refreshToken: String,
    ) = tx.transactional {
        userRepository.revokeSpecificRefreshToken(
            userId = userId,
            token = tokenManager.hashTokenForStorage(refreshToken),
        )
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
                    val accessToken = tokenManager.generateAccessToken(userId = user.id, role = user.role.name)
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

            val claimed = userRepository.markTokenUsed(record.id)
            if (!claimed) {
                return@transactional AppResult.Error(VerifyEmailError.INVALID_TOKEN)
            }

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
            .onFailure { logger.error(it) { "Failed to resend verification email to user ${user.id}" } }

        return AppResult.Success(Unit)
    }

    override suspend fun requestPasswordReset(email: String) {
        val normalizedEmail = emailValidator.normalize(email)
        val user = tx.transactional { userRepository.findUser(normalizedEmail) } ?: return

        if (user.isLocked) {
            logger.warn { "Password reset requested for locked account: ${user.id}" }
            return
        }

        val resetToken = SecureTokenGenerator.generate()
        val expiresAt = Instant.now().plus(appConfig.email.resetTokenExpirationMinutes, ChronoUnit.MINUTES)

        tx.transactional {
            userRepository.invalidatePasswordResetTokens(user.id)
            userRepository.createPasswordResetToken(user.id, tokenManager.hashTokenForStorage(resetToken), expiresAt)
        }

        runSuspendCatching { emailService.sendPasswordResetEmail(user.email, resetToken) }
            .onFailure { logger.error(it) { "Failed to send password reset email to user ${user.id}" } }
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
