/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.security.PasswordService
import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.core.domain.security.TokenManager
import com.mozgobolt.core.domain.validation.DISPLAY_NAME_MAX_LENGTH
import com.mozgobolt.core.domain.validation.EmailValidator
import com.mozgobolt.core.domain.validation.PasswordValidationError
import com.mozgobolt.core.domain.validation.PasswordValidator
import com.mozgobolt.core.domain.validation.normalizeInternationalPhoneNumber
import com.mozgobolt.core.domain.validation.normalizePhoneNumber
import com.mozgobolt.core.domain.validation.validateDisplayName
import com.mozgobolt.core.domain.validation.validateMessengerUsername
import com.mozgobolt.core.modules.AppConfig
import com.mozgobolt.feature.user.domain.UserEmailOutboxRepository
import com.mozgobolt.feature.user.domain.UserRepository
import com.mozgobolt.feature.user.domain.UserService
import com.mozgobolt.feature.user.domain.model.AuthResponse
import com.mozgobolt.feature.user.domain.model.ContactInfoError
import com.mozgobolt.feature.user.domain.model.CurrentUserProfile
import com.mozgobolt.feature.user.domain.model.DriverContactInfo
import com.mozgobolt.feature.user.domain.model.LoginError
import com.mozgobolt.feature.user.domain.model.PasswordResetError
import com.mozgobolt.feature.user.domain.model.PasswordResetToken
import com.mozgobolt.feature.user.domain.model.RefreshError
import com.mozgobolt.feature.user.domain.model.RegisterError
import com.mozgobolt.feature.user.domain.model.TokenValidationResult
import com.mozgobolt.feature.user.domain.model.User
import com.mozgobolt.feature.user.domain.model.UserContactInfo
import com.mozgobolt.feature.user.domain.model.UserEmailType
import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.user.domain.model.VerificationTokenRecord
import com.mozgobolt.feature.user.domain.model.VerifyEmailError
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

private fun PasswordValidationError.toRegisterError(): RegisterError =
    when (this) {
        PasswordValidationError.INVALID -> RegisterError.WEAK_PASSWORD
        PasswordValidationError.BREACH_CHECK_UNAVAILABLE -> RegisterError.PASSWORD_CHECK_UNAVAILABLE
    }

private fun PasswordValidationError.toPasswordResetError(): PasswordResetError =
    when (this) {
        PasswordValidationError.INVALID -> PasswordResetError.WEAK_PASSWORD
        PasswordValidationError.BREACH_CHECK_UNAVAILABLE -> PasswordResetError.PASSWORD_CHECK_UNAVAILABLE
    }

private fun VerificationTokenRecord.validationErrorAt(now: Instant): VerifyEmailError? =
    when {
        used -> VerifyEmailError.INVALID_TOKEN
        expiresAt.isBefore(now) -> VerifyEmailError.EXPIRED_TOKEN
        else -> null
    }

private fun PasswordResetToken.validationErrorAt(now: Instant): PasswordResetError? =
    when {
        used -> PasswordResetError.INVALID_TOKEN
        expiresAt.isBefore(now) -> PasswordResetError.EXPIRED_TOKEN
        else -> null
    }

@Suppress("LongParameterList") // These collaborators are the explicit ports for one transactional user use case.
class UserServiceI(
    private val userRepository: UserRepository,
    private val passwordService: PasswordService,
    private val tokenManager: TokenManager,
    private val passwordValidator: PasswordValidator,
    private val emailValidator: EmailValidator,
    private val emailOutboxRepository: UserEmailOutboxRepository,
    private val appConfig: AppConfig,
    private val tx: TransactionalRunner,
) : UserService {
    private val loginOperations =
        UserLoginOperationsI(
            userRepository = userRepository,
            passwordService = passwordService,
            tokenManager = tokenManager,
            emailValidator = emailValidator,
            tx = tx,
        )

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

        if (validateDisplayName(name, DISPLAY_NAME_MAX_LENGTH).isNotEmpty()) {
            return AppResult.Error(RegisterError.INVALID_NAME)
        }

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

        passwordValidator.validate(password).fold(
            onSuccess = {},
            onError = { error -> return AppResult.Error(error.toRegisterError()) },
        )

        val hashedPassword = passwordService.hashPassword(password)

        val wasRegistered =
            tx.transactional {
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
                    ) ?: return@transactional false

                val verificationToken = SecureTokenGenerator.generate()
                val expiresAt = Instant.now().plus(appConfig.email.verificationTokenExpirationHours, ChronoUnit.HOURS)
                val tokenHash = tokenManager.hashTokenForStorage(verificationToken)
                userRepository.createVerificationToken(
                    newUser.id,
                    tokenHash,
                    expiresAt,
                )
                emailOutboxRepository.enqueue(
                    userId = newUser.id,
                    type = UserEmailType.VERIFICATION,
                    tokenHash = tokenHash,
                    rawToken = verificationToken,
                    expiresAt = expiresAt,
                )
                true
            }
        if (!wasRegistered) return AppResult.Error(RegisterError.ALREADY_EXISTS)

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

    override suspend fun findCurrentUser(userId: Int): CurrentUserProfile? =
        tx.transactional {
            userRepository.findUserById(userId)?.let { user ->
                CurrentUserProfile(
                    id = user.id,
                    email = user.email,
                    name = user.name,
                    role = user.role,
                    emailVerified = user.isEmailVerified,
                    contactInfo =
                        UserContactInfo(
                            phoneNumber = user.phoneNumber,
                            phoneNumberVisible = user.phoneNumberVisible,
                            whatsappNumber = user.whatsappNumber,
                            whatsappVisible = user.whatsappVisible,
                            viberNumber = user.viberNumber,
                            viberVisible = user.viberVisible,
                            messengerUsername = user.messengerUsername,
                            messengerVisible = user.messengerVisible,
                        ),
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
        val wasUpdated = tx.transactional { userRepository.updateContactInfo(userId, normalized) }
        if (!wasUpdated) return AppResult.Error(ContactInfoError.USER_NOT_FOUND)

        return AppResult.Success(normalized)
    }

    override suspend fun signInUser(
        password: String,
        email: String,
    ): AppResult<AuthResponse, LoginError> = loginOperations.signInUser(password, email)

    override suspend fun refreshToken(oldRefreshToken: String): AppResult<AuthResponse, RefreshError> {
        val userId =
            tokenManager.verifyAndGetUserIdFromRefreshToken(oldRefreshToken)
                ?: return AppResult.Error(RefreshError.INVALID_CREDENTIALS)

        val hashedOldToken = tokenManager.hashTokenForStorage(oldRefreshToken)

        return tx.transactional {
            val user =
                userRepository.findUserByIdForUpdate(userId)
                    ?: return@transactional AppResult.Error(RefreshError.INVALID_CREDENTIALS)

            when (val result = userRepository.validateAndRevokeRefreshToken(userId, hashedOldToken)) {
                is TokenValidationResult.Valid -> {
                    val refreshToken =
                        tokenManager.generateRefreshToken(
                            userId = user.id,
                            familyExpiresAt = result.familyExpiresAt,
                        )

                    userRepository.saveRefreshToken(
                        userId = user.id,
                        token = tokenManager.hashTokenForStorage(refreshToken),
                        familyId = result.familyId,
                        familyExpiresAt = result.familyExpiresAt,
                    )
                    val accessToken =
                        tokenManager.generateAccessToken(
                            userId = user.id,
                            role = user.role.name,
                            familyId = result.familyId,
                        )

                    AppResult.Success(
                        AuthResponse(accessToken = accessToken, refreshToken = refreshToken),
                    )
                }

                is TokenValidationResult.AlreadyRevoked -> {
                    logger.warn {
                        "Refresh token reuse detected for user $userId. " +
                            "Revoking the token family — possible token theft."
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
        val hashedToken = tokenManager.hashTokenForStorage(token)
        return tx.transactional {
            val record =
                userRepository.findVerificationToken(hashedToken)
                    ?: return@transactional AppResult.Error(VerifyEmailError.INVALID_TOKEN)

            record.validationErrorAt(Instant.now())?.let { return@transactional AppResult.Error(it) }

            val user =
                userRepository.findUserByIdForUpdate(record.userId)
                    ?: return@transactional AppResult.Error(VerifyEmailError.INVALID_TOKEN)
            val currentRecord =
                userRepository.findVerificationToken(hashedToken)
                    ?: return@transactional AppResult.Error(VerifyEmailError.INVALID_TOKEN)

            currentRecord.validationErrorAt(Instant.now())?.let { return@transactional AppResult.Error(it) }

            if (user.isEmailVerified) {
                return@transactional AppResult.Error(VerifyEmailError.ALREADY_VERIFIED)
            }

            val wasClaimed = userRepository.markTokenUsed(currentRecord.id)
            if (!wasClaimed) {
                return@transactional AppResult.Error(VerifyEmailError.INVALID_TOKEN)
            }

            userRepository.markEmailVerified(user.id)
            userRepository.invalidateVerificationTokens(user.id)
            emailOutboxRepository.cancelPending(user.id, UserEmailType.VERIFICATION)

            logger.info { "Email verified for user ${user.id}" }
            AppResult.Success(Unit)
        }
    }

    override suspend fun resendVerificationEmail(userId: Int): AppResult<Unit, VerifyEmailError> =
        tx.transactional {
            val user =
                userRepository.findUserByIdForUpdate(userId)
                    ?: return@transactional AppResult.Error(VerifyEmailError.INVALID_TOKEN)
            if (user.isEmailVerified) {
                return@transactional AppResult.Error(VerifyEmailError.ALREADY_VERIFIED)
            }

            val verificationToken = SecureTokenGenerator.generate()
            val expiresAt =
                Instant.now().plus(appConfig.email.verificationTokenExpirationHours, ChronoUnit.HOURS)
            val tokenHash = tokenManager.hashTokenForStorage(verificationToken)

            userRepository.invalidateVerificationTokens(userId)
            emailOutboxRepository.cancelPending(userId, UserEmailType.VERIFICATION)
            userRepository.createVerificationToken(userId, tokenHash, expiresAt)
            emailOutboxRepository.enqueue(
                userId = userId,
                type = UserEmailType.VERIFICATION,
                tokenHash = tokenHash,
                rawToken = verificationToken,
                expiresAt = expiresAt,
            )

            AppResult.Success(Unit)
        }

    override suspend fun requestPasswordReset(email: String) {
        val normalizedEmail = emailValidator.normalize(email)
        tx.transactional {
            val foundUser = userRepository.findUser(normalizedEmail) ?: return@transactional
            val user = userRepository.findUserByIdForUpdate(foundUser.id) ?: return@transactional
            if (user.isLocked) {
                logger.warn { "Password reset requested for locked account: ${user.id}" }
                return@transactional
            }

            val resetToken = SecureTokenGenerator.generate()
            val expiresAt =
                Instant.now().plus(appConfig.email.resetTokenExpirationMinutes, ChronoUnit.MINUTES)
            val tokenHash = tokenManager.hashTokenForStorage(resetToken)
            userRepository.invalidatePasswordResetTokens(user.id)
            emailOutboxRepository.cancelPending(user.id, UserEmailType.PASSWORD_RESET)
            userRepository.createPasswordResetToken(user.id, tokenHash, expiresAt)
            emailOutboxRepository.enqueue(
                userId = user.id,
                type = UserEmailType.PASSWORD_RESET,
                tokenHash = tokenHash,
                rawToken = resetToken,
                expiresAt = expiresAt,
            )
        }
    }

    override suspend fun validatePasswordResetToken(token: String): AppResult<String, PasswordResetError> {
        val record =
            tx.transactional { userRepository.findPasswordResetToken(tokenManager.hashTokenForStorage(token)) }
                ?: return AppResult.Error(PasswordResetError.INVALID_TOKEN)

        record.validationErrorAt(Instant.now())?.let { return AppResult.Error(it) }

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
        val hashedToken = tokenManager.hashTokenForStorage(token)
        val record =
            tx.transactional { userRepository.findPasswordResetToken(hashedToken) }
                ?: return AppResult.Error(PasswordResetError.INVALID_TOKEN)

        if (record.used) {
            return AppResult.Error(PasswordResetError.INVALID_TOKEN)
        }

        if (record.expiresAt.isBefore(Instant.now())) {
            return AppResult.Error(PasswordResetError.EXPIRED_TOKEN)
        }

        passwordValidator.validate(newPassword).fold(
            onSuccess = {},
            onError = { error -> return AppResult.Error(error.toPasswordResetError()) },
        )

        val userForPasswordCheck =
            findCurrentPasswordResetUser(hashedToken, record.userId).fold(
                onSuccess = { it },
                onError = { error -> return AppResult.Error(error) },
            )
        val isSameAsOld =
            passwordService.verifyPassword(password = newPassword, hash = userForPasswordCheck.passwordHash)
        val newHashedPassword = if (isSameAsOld) null else passwordService.hashPassword(newPassword)

        return completePasswordReset(
            record = record,
            hashedToken = hashedToken,
            expectedPasswordHash = userForPasswordCheck.passwordHash,
            isSameAsOld = isSameAsOld,
            newHashedPassword = newHashedPassword,
        )
    }

    private suspend fun completePasswordReset(
        record: PasswordResetToken,
        hashedToken: String,
        expectedPasswordHash: String,
        isSameAsOld: Boolean,
        newHashedPassword: String?,
    ): AppResult<Unit, PasswordResetError> =
        tx.transactional {
            val user =
                userRepository.findUserByIdForUpdate(record.userId)
                    ?: return@transactional AppResult.Error(PasswordResetError.USER_NOT_FOUND)

            val currentRecord =
                userRepository.findPasswordResetToken(hashedToken)
                    ?: return@transactional AppResult.Error(PasswordResetError.INVALID_TOKEN)
            currentRecord.validationErrorAt(Instant.now())?.let { return@transactional AppResult.Error(it) }

            if (user.isLocked) {
                logger.warn { "Password reset attempt on locked account: ${user.id}" }
                return@transactional AppResult.Error(PasswordResetError.ACCOUNT_LOCKED)
            }

            if (user.passwordHash != expectedPasswordHash) {
                return@transactional AppResult.Error(PasswordResetError.INVALID_TOKEN)
            }
            if (isSameAsOld) {
                return@transactional AppResult.Error(PasswordResetError.SAME_AS_OLD)
            }

            val usedAt = Instant.now()
            currentRecord.validationErrorAt(usedAt)?.let { return@transactional AppResult.Error(it) }

            val wasClaimed = userRepository.markPasswordResetTokenUsed(currentRecord.id, usedAt)
            if (!wasClaimed) {
                return@transactional AppResult.Error(PasswordResetError.INVALID_TOKEN)
            }

            userRepository.updatePassword(user.id, checkNotNull(newHashedPassword))
            userRepository.invalidatePasswordResetTokens(user.id)
            emailOutboxRepository.cancelPending(user.id, UserEmailType.PASSWORD_RESET)
            userRepository.revokeAllTokensForUser(user.id)

            logger.info { "Password reset completed for user ${user.id}" }
            AppResult.Success(Unit)
        }

    private suspend fun findCurrentPasswordResetUser(
        hashedToken: String,
        userId: Int,
    ): AppResult<User, PasswordResetError> =
        tx.transactional {
            val currentRecord =
                userRepository.findPasswordResetToken(hashedToken)
                    ?: return@transactional AppResult.Error(PasswordResetError.INVALID_TOKEN)
            if (currentRecord.userId != userId) {
                return@transactional AppResult.Error(PasswordResetError.INVALID_TOKEN)
            }
            currentRecord.validationErrorAt(Instant.now())?.let {
                return@transactional AppResult.Error(it)
            }

            val user =
                userRepository.findUserById(userId)
                    ?: return@transactional AppResult.Error(PasswordResetError.USER_NOT_FOUND)
            if (user.isLocked) {
                logger.warn { "Password reset attempt on locked account: ${user.id}" }
                return@transactional AppResult.Error(PasswordResetError.ACCOUNT_LOCKED)
            }

            AppResult.Success(user)
        }
}
