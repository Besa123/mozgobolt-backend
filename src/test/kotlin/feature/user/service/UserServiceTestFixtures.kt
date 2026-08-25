package com.besa.shelflife.feature.user.service

import com.besa.shelflife.core.data.validator.StandardEmailValidator
import com.besa.shelflife.core.data.validator.StandardPasswordValidator
import com.besa.shelflife.core.database.TransactionalRunner
import com.besa.shelflife.core.domain.AppResult
import com.besa.shelflife.core.domain.email.EmailService
import com.besa.shelflife.core.domain.security.PasswordService
import com.besa.shelflife.core.domain.security.TokenManager
import com.besa.shelflife.core.modules.AppConfig
import com.besa.shelflife.feature.user.domain.UserRepository
import com.besa.shelflife.feature.user.domain.model.TokenValidationResult
import com.besa.shelflife.feature.user.domain.model.User
import com.besa.shelflife.feature.user.domain.model.VerificationTokenRecord
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.fail

data class Harness(
    val service: UserServiceI,
    val repository: FakeUserRepository,
    val emailService: RecordingEmailService,
    val passwordService: CountingPasswordService,
    val tokenManager: FakeTokenManager,
)

fun newHarness(): Harness {
    val repository = FakeUserRepository()
    val emailService = RecordingEmailService()
    val passwordService = CountingPasswordService()
    val tokenManager = FakeTokenManager()
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
            passwordValidator = StandardPasswordValidator(),
            emailValidator = StandardEmailValidator(),
            emailService = emailService,
            appConfig = appConfig,
            tx = NoopTransactionalRunner(),
        )
    return Harness(service, repository, emailService, passwordService, tokenManager)
}

fun <T> assertError(
    expected: T,
    result: AppResult<*, T>,
) {
    result.fold(
        onSuccess = { fail("expected error $expected but got success value $it") },
        onError = { actual -> assertEquals(expected, actual) },
    )
}

class CountingPasswordService : PasswordService {
    var verifyCallCount: Int = 0
        private set

    override fun hashPassword(password: String): String = "hashed:$password"

    override fun verifyPassword(
        password: String,
        hash: String,
    ): Boolean {
        verifyCallCount++
        return hash == "hashed:$password"
    }
}

class FakeTokenManager : TokenManager {
    private var counter = 0

    override fun generateAccessToken(userId: Int): String = "access:$userId:${counter++}"

    override fun generateRefreshToken(userId: Int): String = "refresh:$userId:${counter++}"

    override fun verifyAndGetUserIdFromRefreshToken(token: String): Int? =
        token
            .takeIf { it.startsWith("refresh:") }
            ?.split(":")
            ?.getOrNull(1)
            ?.toIntOrNull()

    override fun hashTokenForStorage(token: String): String = "hash:$token"
}

class RecordingEmailService : EmailService {
    val sentTokens = mutableListOf<Pair<String, String>>()

    var failureToThrow: Throwable? = null

    override suspend fun sendVerificationEmail(
        to: String,
        token: String,
    ) {
        failureToThrow?.let { throw it }
        sentTokens += to to token
    }
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
        val isRevoked: Boolean,
    )

    private val usersById = mutableMapOf<Int, User>()
    private val refreshTokens = mutableMapOf<Int, RefreshTokenRecord>()
    private val verificationTokens = mutableMapOf<Int, VerificationTokenRecord>()
    private var nextUserId = 1
    private var nextRefreshTokenId = 1
    private var nextVerificationTokenId = 1

    fun seedVerifiedUser(
        email: String,
        password: String,
        verified: Boolean = true,
    ): User {
        val user =
            User(
                id = nextUserId++,
                email = email,
                name = "Seeded User",
                passwordHash = "hashed:$password",
                isEmailVerified = verified,
            )
        usersById[user.id] = user
        return user
    }

    override suspend fun findUser(email: String): User? = usersById.values.find { it.email == email }

    override suspend fun findUserById(userId: Int): User? = usersById[userId]

    override suspend fun createUser(
        email: String,
        password: String,
        name: String,
    ): User {
        val user = User(id = nextUserId++, email = email, name = name, passwordHash = password)
        usersById[user.id] = user
        return user
    }

    override suspend fun saveRefreshToken(
        userId: Int,
        token: String,
        familyId: String,
    ) {
        val id = nextRefreshTokenId++
        refreshTokens[id] = RefreshTokenRecord(id, userId, token, familyId, isRevoked = false)
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
        return TokenValidationResult.Valid(familyId = record.familyId)
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

    override suspend fun revokeSpecificRefreshToken(token: String) {
        revokeWhere { it.token == token }
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

    override suspend fun findVerificationToken(token: String): VerificationTokenRecord? =
        verificationTokens.values.find { it.token == token }

    override suspend fun markTokenUsed(tokenId: Int) {
        val record = verificationTokens[tokenId] ?: return
        verificationTokens[tokenId] = record.copy(used = true)
    }

    override suspend fun markEmailVerified(userId: Int) {
        val user = usersById[userId] ?: return
        usersById[userId] = user.copy(isEmailVerified = true)
    }

    override suspend fun invalidateVerificationTokens(userId: Int) {
        verificationTokens.replaceAll { _, record -> if (record.userId == userId) record.copy(used = true) else record }
    }
}
