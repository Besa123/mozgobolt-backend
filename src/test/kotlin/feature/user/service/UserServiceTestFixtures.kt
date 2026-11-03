/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.service

import com.mozgobolt.core.data.validator.StandardEmailValidator
import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.security.PasswordBreachCheckError
import com.mozgobolt.core.domain.security.PasswordPolicy
import com.mozgobolt.core.domain.security.PasswordService
import com.mozgobolt.core.domain.security.TokenManager
import com.mozgobolt.core.domain.validation.PasswordValidationError
import com.mozgobolt.core.domain.validation.PasswordValidator
import com.mozgobolt.core.modules.AppConfig
import com.mozgobolt.feature.user.domain.UserEmailOutboxRepository
import com.mozgobolt.feature.user.domain.UserRepository
import com.mozgobolt.feature.user.domain.model.PasswordResetToken
import com.mozgobolt.feature.user.domain.model.TokenValidationResult
import com.mozgobolt.feature.user.domain.model.User
import com.mozgobolt.feature.user.domain.model.UserContactInfo
import com.mozgobolt.feature.user.domain.model.UserEmailOutboxDelivery
import com.mozgobolt.feature.user.domain.model.UserEmailType
import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.user.domain.model.VerificationTokenRecord
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentSessionTerminator
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.fail

data class Harness(
    val service: UserServiceI,
    val sessionService: UserSessionServiceI,
    val repository: FakeUserRepository,
    val passwordValidator: FakePasswordValidator,
    val emailOutbox: RecordingUserEmailOutboxRepository,
    val passwordService: CountingPasswordService,
    val tokenManager: FakeTokenManager,
    val assignmentTerminator: FakeVehicleAssignmentSessionTerminator,
)

fun newHarness(): Harness {
    val repository = FakeUserRepository()
    val emailOutbox = RecordingUserEmailOutboxRepository()
    val passwordService = CountingPasswordService()
    val tokenManager = FakeTokenManager()
    val passwordValidator = FakePasswordValidator()
    val assignmentTerminator = FakeVehicleAssignmentSessionTerminator()
    val appConfig =
        AppConfig(
            database = AppConfig.Database(url = "unused", user = "unused", password = "unused"),
            jwt = AppConfig.Jwt(secret = "unused", issuer = "unused", audience = "unused"),
            security = AppConfig.Security(passwordPepper = "unused"),
            cors = AppConfig.Cors(),
            email = AppConfig.Email(verificationTokenExpirationHours = 24),
        )
    val service =
        UserServiceI(
            userRepository = repository,
            passwordService = passwordService,
            tokenManager = tokenManager,
            passwordValidator = passwordValidator,
            emailValidator = StandardEmailValidator(),
            emailOutboxRepository = emailOutbox,
            appConfig = appConfig,
            tx = NoopTransactionalRunner(),
        )
    val sessionService =
        UserSessionServiceI(
            userRepository = repository,
            passwordService = passwordService,
            vehicleAssignmentSessionTerminator = assignmentTerminator,
            tx = NoopTransactionalRunner(),
        )
    return Harness(
        service,
        sessionService,
        repository,
        passwordValidator,
        emailOutbox,
        passwordService,
        tokenManager,
        assignmentTerminator,
    )
}

fun <T> assertError(
    expected: T,
    result: AppResult<*, T>,
) {
    result.fold(
        onSuccess = { fail("expected an error, but the result was successful") },
        onError = { actual -> assertEquals(expected, actual) },
    )
}

class FakePasswordValidator : PasswordValidator {
    var breachCheckResult: AppResult<Boolean, PasswordBreachCheckError> = AppResult.Success(false)
    val checkedPasswords = mutableListOf<String>()

    override suspend fun validate(password: String): AppResult<Unit, PasswordValidationError> {
        if (!PasswordPolicy.isValid(password)) return AppResult.Error(PasswordValidationError.INVALID)
        checkedPasswords += password
        return when (val result = breachCheckResult) {
            is AppResult.Success -> {
                if (result.data) {
                    AppResult.Error(PasswordValidationError.INVALID)
                } else {
                    AppResult.Success(Unit)
                }
            }

            is AppResult.Error -> {
                AppResult.Error(PasswordValidationError.BREACH_CHECK_UNAVAILABLE)
            }
        }
    }
}

class CountingPasswordService : PasswordService {
    var verifyCallCount: Int = 0
        private set
    var verifyDelayMillis: Long = 0

    override suspend fun hashPassword(password: String): String = "hashed:$password"

    override suspend fun verifyPassword(
        password: String,
        hash: String,
    ): Boolean {
        verifyCallCount++
        if (verifyDelayMillis > 0) delay(verifyDelayMillis)
        return hash == "hashed:$password"
    }
}

class FakeTokenManager : TokenManager {
    private var counter = 0
    var lastGeneratedAccessFamilyId: String? = null
        private set

    override fun generateAccessToken(
        userId: Int,
        role: String,
        familyId: String,
    ): String {
        lastGeneratedAccessFamilyId = familyId
        return "access:$userId:$role:$familyId:${counter++}"
    }

    override fun generateRefreshToken(
        userId: Int,
        familyExpiresAt: Instant?,
    ): String = "refresh:$userId:${counter++}"

    override fun verifyAndGetUserIdFromRefreshToken(token: String): Int? =
        token
            .takeIf { it.startsWith("refresh:") }
            ?.split(":")
            ?.getOrNull(1)
            ?.toIntOrNull()

    override fun hashTokenForStorage(token: String): String = "hash:$token"
}

class FakeVehicleAssignmentSessionTerminator : VehicleAssignmentSessionTerminator {
    val endedForUsers = mutableListOf<Int>()
    val notifiedAssignments = mutableListOf<VehicleAssignment>()
    var activeAssignment: VehicleAssignment? = null

    override suspend fun findActiveForVendor(vendorUserId: Int): VehicleAssignment? =
        activeAssignment?.takeIf { it.vendorUserId == vendorUserId }

    override suspend fun endActiveAssignmentForVendor(vendorUserId: Int): VehicleAssignment? {
        endedForUsers += vendorUserId
        return activeAssignment.also { activeAssignment = null }
    }

    override fun notifyEndedAssignments(assignments: List<VehicleAssignment>) {
        notifiedAssignments += assignments
    }
}

class RecordingUserEmailOutboxRepository : UserEmailOutboxRepository {
    data class QueuedEmail(
        val id: Long,
        val userId: Int,
        val type: UserEmailType,
        val tokenHash: String,
        val rawToken: String,
        val expiresAt: Instant,
    )

    val history = mutableListOf<QueuedEmail>()
    private val cancelledIds = mutableSetOf<Long>()
    val pending: List<QueuedEmail>
        get() = history.filterNot { it.id in cancelledIds }
    val verificationEmails: List<QueuedEmail>
        get() = history.filter { it.type == UserEmailType.VERIFICATION }
    val passwordResetEmails: List<QueuedEmail>
        get() = history.filter { it.type == UserEmailType.PASSWORD_RESET }
    var failureToThrow: Throwable? = null
    private var nextId = 1L

    override suspend fun enqueue(
        userId: Int,
        type: UserEmailType,
        tokenHash: String,
        rawToken: String,
        expiresAt: Instant,
    ) {
        failureToThrow?.let { throw it }
        history += QueuedEmail(nextId++, userId, type, tokenHash, rawToken, expiresAt)
    }

    override suspend fun cancelPending(
        userId: Int,
        type: UserEmailType,
    ) {
        history.filter { it.userId == userId && it.type == type }.forEach { cancelledIds += it.id }
    }

    override suspend fun purgeExpired(now: Instant): Int {
        val expiredIds = history.filter { it.expiresAt.isBefore(now) }.map(QueuedEmail::id).toSet()
        history.removeAll { it.id in expiredIds }
        cancelledIds.removeAll(expiredIds)
        return expiredIds.size
    }

    override suspend fun claimBatch(
        now: Instant,
        leaseUntil: Instant,
        limit: Int,
    ): List<UserEmailOutboxDelivery> = error("not exercised by service tests")

    override suspend fun isCurrent(
        id: Long,
        leaseToken: String,
        now: Instant,
    ): Boolean = error("not exercised by service tests")

    override suspend fun markDelivered(
        id: Long,
        leaseToken: String,
    ): Boolean = error("not exercised by service tests")

    override suspend fun discard(
        id: Long,
        leaseToken: String,
    ): Boolean = error("not exercised by service tests")

    override suspend fun scheduleRetry(
        id: Long,
        leaseToken: String,
        nextAttemptAt: Instant,
        failureType: String,
    ): Boolean = error("not exercised by service tests")

    override suspend fun markFailed(
        id: Long,
        leaseToken: String,
        failedAt: Instant,
        failureType: String,
    ): Boolean = error("not exercised by service tests")
}

class NoopTransactionalRunner : TransactionalRunner {
    override suspend fun <T> transactional(block: suspend () -> T): T = block()
}

class FakeUserRepository : UserRepository {
    private data class RefreshTokenRecord(
        val id: Int,
        val userId: Int,
        val token: String,
        val familyId: String,
        val familyExpiresAt: Instant,
        val isRevoked: Boolean,
    )

    private val usersById = mutableMapOf<Int, User>()
    private val refreshTokens = mutableMapOf<Int, RefreshTokenRecord>()
    private val verificationTokens = mutableMapOf<Int, VerificationTokenRecord>()
    private val passwordResetTokens = mutableMapOf<Int, PasswordResetToken>()
    var failNextVerificationTokenClaim = false
    var failNextPasswordResetTokenClaim = false
    var returnNullOnNextLockedUserRead = false
    var lockUntilOnNextLockedUserRead: Instant? = null
    var passwordHashOnNextLockedUserRead: String? = null
    var failedLoginAttemptsOnNextLockedUserRead: Int? = null
    var removeVerificationTokenOnSecondLookup = false
    var expireVerificationTokenOnSecondLookup = false
    var removePasswordResetTokenOnSecondLookup = false
    var expirePasswordResetTokenOnSecondLookup = false
    val authenticationTokenPurgeCutoffs = mutableListOf<Pair<Instant, Instant>>()
    var authenticationTokensToPurge = 0
    private var verificationTokenLookupCount = 0
    private var passwordResetTokenLookupCount = 0
    private var nextUserId = 1
    private var nextRefreshTokenId = 1
    private var nextVerificationTokenId = 1
    private var nextPasswordResetTokenId = 1

    fun seedVerifiedUser(
        email: String,
        password: String,
        verified: Boolean = true,
        role: UserRole = UserRole.BUYER,
    ): User {
        val user =
            User(
                id = nextUserId++,
                email = email,
                name = "Seeded User",
                passwordHash = "hashed:$password",
                role = role,
                isEmailVerified = verified,
            )
        usersById[user.id] = user
        return user
    }

    fun hasVerificationToken(userId: Int): Boolean = verificationTokens.values.any { it.userId == userId }

    fun hasPasswordResetToken(userId: Int): Boolean = passwordResetTokens.values.any { it.userId == userId }

    override suspend fun findUser(email: String): User? = usersById.values.find { it.email == email }

    override suspend fun updateContactInfo(
        userId: Int,
        contactInfo: UserContactInfo,
    ): Boolean {
        val user = usersById[userId] ?: return false
        usersById[userId] =
            user.copy(
                phoneNumber = contactInfo.phoneNumber,
                phoneNumberVisible = contactInfo.phoneNumberVisible,
                whatsappNumber = contactInfo.whatsappNumber,
                whatsappVisible = contactInfo.whatsappVisible,
                viberNumber = contactInfo.viberNumber,
                viberVisible = contactInfo.viberVisible,
                messengerUsername = contactInfo.messengerUsername,
                messengerVisible = contactInfo.messengerVisible,
            )
        return true
    }

    override suspend fun findUserById(userId: Int): User? = usersById[userId]

    override suspend fun deleteUser(userId: Int): Boolean {
        if (usersById.remove(userId) == null) return false
        refreshTokens.entries.removeAll { it.value.userId == userId }
        verificationTokens.entries.removeAll { it.value.userId == userId }
        passwordResetTokens.entries.removeAll { it.value.userId == userId }
        return true
    }

    override suspend fun purgeExpiredAuthenticationTokens(
        now: Instant,
        oneTimeTokenRetentionCutoff: Instant,
    ): Int {
        authenticationTokenPurgeCutoffs += now to oneTimeTokenRetentionCutoff
        return authenticationTokensToPurge
    }

    override suspend fun findUserByIdForUpdate(userId: Int): User? {
        if (returnNullOnNextLockedUserRead) {
            returnNullOnNextLockedUserRead = false
            return null
        }

        val user = usersById[userId] ?: return null
        val updated =
            user.copy(
                lockedUntil = lockUntilOnNextLockedUserRead ?: user.lockedUntil,
                passwordHash = passwordHashOnNextLockedUserRead ?: user.passwordHash,
                failedLoginAttempts = failedLoginAttemptsOnNextLockedUserRead ?: user.failedLoginAttempts,
            )
        lockUntilOnNextLockedUserRead = null
        passwordHashOnNextLockedUserRead = null
        failedLoginAttemptsOnNextLockedUserRead = null
        usersById[userId] = updated
        return updated
    }

    override suspend fun createUser(
        email: String,
        password: String,
        name: String,
        role: UserRole,
        phoneNumber: String?,
        whatsappNumber: String?,
        viberNumber: String?,
        messengerUsername: String?,
    ): User? {
        if (usersById.values.any { it.email == email }) return null

        val user =
            User(
                id = nextUserId++,
                email = email,
                name = name,
                passwordHash = password,
                role = role,
                phoneNumber = phoneNumber,
                whatsappNumber = whatsappNumber,
                viberNumber = viberNumber,
                messengerUsername = messengerUsername,
            )
        usersById[user.id] = user
        return user
    }

    override suspend fun saveRefreshToken(
        userId: Int,
        token: String,
        familyId: String,
        familyExpiresAt: Instant?,
    ): Instant {
        val id = nextRefreshTokenId++
        val absoluteExpiry = familyExpiresAt ?: Instant.MAX
        refreshTokens[id] =
            RefreshTokenRecord(id, userId, token, familyId, absoluteExpiry, isRevoked = false)
        return absoluteExpiry
    }

    override suspend fun validateAndRevokeRefreshToken(
        userId: Int,
        token: String,
    ): TokenValidationResult {
        val entry =
            refreshTokens.entries.find { (_, record) -> record.userId == userId && record.token == token }
                ?: return TokenValidationResult.NotFound
        val record = entry.value

        if (record.isRevoked) return TokenValidationResult.AlreadyRevoked(familyId = record.familyId)

        refreshTokens[entry.key] = record.copy(isRevoked = true)
        return TokenValidationResult.Valid(
            familyId = record.familyId,
            familyExpiresAt = record.familyExpiresAt,
        )
    }

    override suspend fun isTokenFamilyActive(
        userId: Int,
        familyId: String,
        now: Instant,
    ): Boolean =
        refreshTokens.values.any {
            it.userId == userId &&
                it.familyId == familyId &&
                !it.isRevoked &&
                it.familyExpiresAt.isAfter(now)
        }

    override suspend fun recordFailedLogin(
        userId: Int,
        lockUntil: Instant?,
    ) {
        val user = usersById[userId] ?: return
        usersById[userId] = user.copy(failedLoginAttempts = user.failedLoginAttempts + 1, lockedUntil = lockUntil)
    }

    override suspend fun resetFailedLogins(userId: Int) {
        val user = usersById[userId] ?: return
        usersById[userId] = user.copy(failedLoginAttempts = 0, lockedUntil = null)
    }

    override suspend fun revokeTokenFamily(familyId: String) {
        revokeWhere { it.familyId == familyId }
    }

    override suspend fun revokeAllTokensForUser(userId: Int) {
        revokeWhere { it.userId == userId }
    }

    override suspend fun revokeSpecificRefreshToken(
        userId: Int,
        token: String,
    ) {
        revokeWhere { it.userId == userId && it.token == token }
    }

    private fun revokeWhere(predicate: (RefreshTokenRecord) -> Boolean) {
        refreshTokens.entries
            .filter { (_, record) -> predicate(record) }
            .forEach { (id, record) -> refreshTokens[id] = record.copy(isRevoked = true) }
    }

    override suspend fun createVerificationToken(
        userId: Int,
        token: String,
        expiresAt: Instant,
    ) {
        val id = nextVerificationTokenId++
        verificationTokens[id] = VerificationTokenRecord(id, userId, token, expiresAt, used = false)
    }

    override suspend fun findVerificationToken(token: String): VerificationTokenRecord? {
        val entry = verificationTokens.entries.find { it.value.token == token } ?: return null
        verificationTokenLookupCount++
        if (verificationTokenLookupCount == 2 && removeVerificationTokenOnSecondLookup) {
            verificationTokens.remove(entry.key)
            return null
        }
        if (verificationTokenLookupCount == 2 && expireVerificationTokenOnSecondLookup) {
            val expired = entry.value.copy(expiresAt = Instant.now().minusSeconds(1))
            verificationTokens[entry.key] = expired
            return expired
        }
        return entry.value
    }

    override suspend fun markTokenUsed(tokenId: Int): Boolean {
        if (failNextVerificationTokenClaim) {
            failNextVerificationTokenClaim = false
            return false
        }
        val record = verificationTokens[tokenId] ?: return false
        if (record.used) return false
        verificationTokens[tokenId] = record.copy(used = true)
        return true
    }

    override suspend fun markEmailVerified(userId: Int) {
        val user = usersById[userId] ?: return
        usersById[userId] = user.copy(isEmailVerified = true)
    }

    override suspend fun invalidateVerificationTokens(userId: Int) {
        verificationTokens.replaceAll { _, record -> if (record.userId == userId) record.copy(used = true) else record }
    }

    override suspend fun createPasswordResetToken(
        userId: Int,
        token: String,
        expiresAt: Instant,
    ) {
        val id = nextPasswordResetTokenId++
        passwordResetTokens[id] =
            PasswordResetToken(
                id = id,
                userId = userId,
                token = token,
                expiresAt = expiresAt,
                used = false,
                usedAt = null,
                createdAt = Instant.now(),
            )
    }

    override suspend fun findPasswordResetToken(token: String): PasswordResetToken? {
        val entry = passwordResetTokens.entries.find { it.value.token == token } ?: return null
        passwordResetTokenLookupCount++
        if (passwordResetTokenLookupCount == 2 && removePasswordResetTokenOnSecondLookup) {
            passwordResetTokens.remove(entry.key)
            return null
        }
        if (passwordResetTokenLookupCount == 2 && expirePasswordResetTokenOnSecondLookup) {
            val expired = entry.value.copy(expiresAt = Instant.now().minusSeconds(1))
            passwordResetTokens[entry.key] = expired
            return expired
        }
        return entry.value
    }

    override suspend fun markPasswordResetTokenUsed(
        tokenId: Int,
        usedAt: Instant,
    ): Boolean {
        if (failNextPasswordResetTokenClaim) {
            failNextPasswordResetTokenClaim = false
            return false
        }
        val record = passwordResetTokens[tokenId] ?: return false
        if (record.used) return false
        passwordResetTokens[tokenId] = record.copy(used = true, usedAt = usedAt)
        return true
    }

    override suspend fun invalidatePasswordResetTokens(userId: Int) {
        passwordResetTokens.replaceAll { _, record ->
            if (record.userId == userId) record.copy(used = true) else record
        }
    }

    override suspend fun updatePassword(
        userId: Int,
        newPasswordHash: String,
    ) {
        val user = usersById[userId] ?: return
        usersById[userId] = user.copy(passwordHash = newPasswordHash)
    }
}
