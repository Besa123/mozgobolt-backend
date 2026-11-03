/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user

import com.mozgobolt.core.data.security.PasswordServiceImpl
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.security.PasswordService
import com.mozgobolt.feature.user.data.repository.UserRepositoryI
import com.mozgobolt.feature.user.domain.UserRepository
import com.mozgobolt.feature.user.domain.model.LoginError
import com.mozgobolt.feature.user.domain.model.PasswordResetError
import com.mozgobolt.feature.user.domain.model.RefreshError
import com.mozgobolt.feature.user.domain.model.RegisterError
import com.mozgobolt.feature.user.domain.model.TokenValidationResult
import com.mozgobolt.feature.user.domain.model.User
import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.user.domain.model.VerifyEmailError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class AuthFlowIntegrationTest : RealUserServiceDatabaseTestSupport() {
    @Test
    fun `register, verify, log in and rotate refresh tokens against a real database`() {
        skipIfNoDocker()

        withRealDatabase { harness, database ->
            exerciseAuthFlow(harness.service, harness.emailOutbox)
            exerciseIdempotencyKeysTable(database)
        }
    }

    @Test
    fun `concurrent refresh reuse revokes the rotated token family`() {
        skipIfNoDocker()

        withRealDatabase { harness, _ ->
            harness.service
                .createUser(
                    password = "Str0ngPassphrase1",
                    email = "refresh-race@example.com",
                    name = "Refresh Race",
                    role = UserRole.BUYER,
                ).fold(onSuccess = {}, onError = { fail("registration failed: $it") })
            val original =
                harness.service
                    .signInUser("Str0ngPassphrase1", "refresh-race@example.com")
                    .fold(onSuccess = { it }, onError = { fail("initial sign-in failed: $it") })

            val results =
                coroutineScope {
                    listOf(
                        async(Dispatchers.IO) { harness.service.refreshToken(original.refreshToken) },
                        async(Dispatchers.IO) { harness.service.refreshToken(original.refreshToken) },
                    ).awaitAll()
                }

            assertEquals(1, results.count { it is AppResult.Success })
            assertEquals(
                1,
                results.count { it is AppResult.Error && it.errorType == RefreshError.TOKEN_REUSE_DETECTED },
            )
            val rotatedToken =
                results
                    .single { it is AppResult.Success }
                    .fold(
                        onSuccess = { it.refreshToken },
                        onError = { fail("expected one successful rotation: $it") },
                    )
            assertEquals(
                RefreshError.TOKEN_REUSE_DETECTED,
                harness.service.refreshToken(rotatedToken).fold(
                    onSuccess = { fail("reuse must revoke the entire family, including the rotated token") },
                    onError = { it },
                ),
            )
        }
    }

    @Test
    fun `concurrent duplicate registrations return one success and one conflict without a database exception`() {
        skipIfNoDocker()

        val passwordGate =
            ConcurrentPasswordHashGate(
                delegate = PasswordServiceImpl(pepper = "integration-test-pepper"),
                parties = 2,
            )
        withRealDatabase(passwordService = passwordGate) { harness, _ ->
            val results =
                coroutineScope {
                    val registrations =
                        listOf(
                            async(Dispatchers.IO) {
                                harness.service.createUser(
                                    password = "Str0ngPassphrase1",
                                    email = "racer@example.com",
                                    name = "A",
                                    role = UserRole.BUYER,
                                )
                            },
                            async(Dispatchers.IO) {
                                harness.service.createUser(
                                    password = "Str0ngPassphrase1",
                                    email = "racer@example.com",
                                    name = "B",
                                    role = UserRole.BUYER,
                                )
                            },
                        )
                    try {
                        assertTrue(
                            withContext(Dispatchers.IO) {
                                passwordGate.bothHashesStarted.await(10, TimeUnit.SECONDS)
                            },
                            "both registration attempts must reach the database together",
                        )
                    } finally {
                        passwordGate.releaseHashes.countDown()
                    }
                    registrations.awaitAll()
                }

            assertEquals(1, results.count { it is AppResult.Success }, "exactly one registration must be created")
            assertEquals(
                1,
                results.count { it is AppResult.Error && it.errorType == RegisterError.ALREADY_EXISTS },
                "the database uniqueness race must map to ALREADY_EXISTS, not escape as a SQL exception",
            )
            assertNotNull(harness.tx.transactional { harness.userRepository.findUser("racer@example.com") })
            assertEquals(
                1,
                harness.emailOutbox.verificationEmails.size,
                "only the winning account queues a verification email",
            )
        }
    }
}

class UserAuthenticationConcurrencyRealDatabaseTest : RealUserServiceDatabaseTestSupport() {
    @Test
    fun `an unverified account can sign in as documented and remains unverified`() {
        skipIfNoDocker()

        withRealDatabase { harness, _ ->
            harness.service
                .createUser(
                    password = "Str0ngPassphrase1",
                    email = "unverified@example.com",
                    name = "Unverified",
                    role = UserRole.BUYER,
                ).fold(onSuccess = {}, onError = { fail("registration failed: $it") })

            val signIn =
                harness.service
                    .signInUser(password = "Str0ngPassphrase1", email = "unverified@example.com")
                    .fold(onSuccess = { it }, onError = { fail("unverified sign-in is currently supported: $it") })

            assertTrue(signIn.accessToken.isNotBlank())
            assertFalse(
                assertNotNull(
                    harness.tx.transactional {
                        harness.userRepository.findUser("unverified@example.com")
                    },
                ).isEmailVerified,
            )
        }
    }

    @Test
    fun `concurrent wrong passwords at the lockout threshold serialize against the persisted account state`() {
        skipIfNoDocker()

        val passwordGate =
            ConcurrentPasswordCheckGate(
                delegate = PasswordServiceImpl(pepper = "integration-test-pepper"),
                gatedPassword = "WrongPass1",
                parties = 2,
            )
        withRealDatabase(passwordService = passwordGate) { harness, _ ->
            val userId =
                harness.tx.transactional {
                    val user =
                        checkNotNull(
                            harness.userRepository.createUser(
                                email = "lockout-race@example.com",
                                password = passwordGate.hashPassword("CorrectPass1"),
                                name = "Lockout Race",
                                role = UserRole.BUYER,
                            ),
                        )
                    repeat(4) { harness.userRepository.recordFailedLogin(user.id, lockUntil = null) }
                    user.id
                }

            val results =
                coroutineScope {
                    val first =
                        async(Dispatchers.IO) {
                            harness.service.signInUser("WrongPass1", "lockout-race@example.com")
                        }
                    val second =
                        async(Dispatchers.IO) {
                            harness.service.signInUser("WrongPass1", "lockout-race@example.com")
                        }
                    try {
                        assertTrue(
                            withContext(Dispatchers.IO) { passwordGate.bothChecksStarted.await(10, TimeUnit.SECONDS) },
                            "both requests must verify the stale pre-lockout state before either records a failure",
                        )
                    } finally {
                        passwordGate.releaseChecks.countDown()
                    }
                    listOf(first.await(), second.await())
                }

            assertEquals(
                1,
                results.count { it is AppResult.Error && it.errorType == LoginError.INVALID_CREDENTIALS },
            )
            assertEquals(
                1,
                results.count { it is AppResult.Error && it.errorType == LoginError.ACCOUNT_LOCKED },
                "the request serialized after the threshold is reached must observe the lock",
            )
            val lockedUser = assertNotNull(harness.tx.transactional { harness.userRepository.findUserById(userId) })
            assertEquals(5, lockedUser.failedLoginAttempts)
            assertTrue(
                assertNotNull(lockedUser.lockedUntil).isAfter(
                    java.time.Instant
                        .now()
                        .plusSeconds(30),
                ),
                "the fifth failed attempt must persist the one-minute lockout",
            )
        }
    }
}

class UserPasswordResetRealDatabaseTest : RealUserServiceDatabaseTestSupport() {
    @Test
    fun `password reset changes the stored password and revokes refresh sessions in PostgreSQL`() {
        skipIfNoDocker()

        withRealDatabase { harness, _ ->
            harness.service
                .createUser(
                    password = "OldPassphrase1x",
                    email = "reset-flow@example.com",
                    name = "Reset Flow",
                    role = UserRole.BUYER,
                ).fold(onSuccess = {}, onError = { fail("registration failed: $it") })
            val oldSession =
                harness.service
                    .signInUser("OldPassphrase1x", "reset-flow@example.com")
                    .fold(onSuccess = { it }, onError = { fail("initial sign-in failed: $it") })

            harness.service.requestPasswordReset("reset-flow@example.com")
            val resetToken =
                harness.emailOutbox.passwordResetEmails
                    .single()
                    .rawToken
            harness.service
                .confirmPasswordReset(resetToken, "NewPassphrase2x")
                .fold(onSuccess = {}, onError = { fail("password reset failed: $it") })

            assertEquals(
                PasswordResetError.INVALID_TOKEN,
                harness.service.validatePasswordResetToken(resetToken).fold(
                    onSuccess = { fail("a consumed reset token must not validate") },
                    onError = { it },
                ),
            )
            assertEquals(
                LoginError.INVALID_CREDENTIALS,
                harness.service.signInUser("OldPassphrase1x", "reset-flow@example.com").fold(
                    onSuccess = { fail("the old password must stop authenticating") },
                    onError = { it },
                ),
            )
            assertEquals(
                RefreshError.TOKEN_REUSE_DETECTED,
                harness.service.refreshToken(oldSession.refreshToken).fold(
                    onSuccess = { fail("the old refresh session must be revoked") },
                    onError = { it },
                ),
            )
            harness.service
                .signInUser("NewPassphrase2x", "reset-flow@example.com")
                .fold(onSuccess = {}, onError = { fail("the new password must authenticate: $it") })
        }
    }

    @Test
    fun `failed password update rolls back reset token consumption and session revocation`() {
        skipIfNoDocker()

        val realRepository = UserRepositoryI()
        val failingRepository = FailingPasswordUpdateRepository(realRepository)
        withRealDatabase(userRepository = failingRepository) { harness, _ ->
            harness.service
                .createUser(
                    password = "OldPassphrase1x",
                    email = "reset-rollback@example.com",
                    name = "Reset Rollback",
                    role = UserRole.BUYER,
                ).fold(onSuccess = {}, onError = { fail("registration failed: $it") })
            val user =
                assertNotNull(
                    harness.tx.transactional {
                        realRepository.findUser("reset-rollback@example.com")
                    },
                )
            val oldSession =
                harness.service
                    .signInUser("OldPassphrase1x", user.email)
                    .fold(onSuccess = { it }, onError = { fail("initial sign-in failed: $it") })
            harness.service.requestPasswordReset(user.email)
            val resetToken =
                harness.emailOutbox.passwordResetEmails
                    .single()
                    .rawToken

            assertFailsWith<IllegalStateException> {
                harness.service.confirmPasswordReset(resetToken, "NewPassphrase2x")
            }

            val persistedToken =
                assertNotNull(
                    harness.tx.transactional {
                        realRepository.findPasswordResetToken(harness.tokenManager.hashTokenForStorage(resetToken))
                    },
                )
            assertFalse(persistedToken.used, "failed password update must roll back the token claim")
            assertEquals(
                user.passwordHash,
                harness.tx.transactional { realRepository.findUserById(user.id)?.passwordHash },
                "failed password update must preserve the existing password hash",
            )
            assertTrue(
                harness.tx.transactional {
                    realRepository.validateAndRevokeRefreshToken(
                        userId = user.id,
                        token = harness.tokenManager.hashTokenForStorage(oldSession.refreshToken),
                    ) is TokenValidationResult.Valid
                },
                "failed reset must leave the pre-existing refresh session active",
            )
        }
    }

    @Test
    fun `concurrent password reset confirmation consumes the token at most once`() {
        skipIfNoDocker()

        val delegate = UserRepositoryI()
        val repository = CoordinatedPasswordResetReadRepository(delegate, firstReadParties = 2)
        withRealDatabase(userRepository = repository) { harness, _ ->
            harness.service
                .createUser(
                    password = "OldPassphrase1x",
                    email = "reset-race@example.com",
                    name = "Reset Race",
                    role = UserRole.BUYER,
                ).fold(onSuccess = {}, onError = { fail("registration failed: $it") })
            harness.service.requestPasswordReset("reset-race@example.com")
            val resetToken =
                harness.emailOutbox.passwordResetEmails
                    .single()
                    .rawToken

            val results =
                coroutineScope {
                    listOf(
                        async(Dispatchers.IO) { harness.service.confirmPasswordReset(resetToken, "NewPassphrase2x") },
                        async(Dispatchers.IO) { harness.service.confirmPasswordReset(resetToken, "OtherPassphrase3x") },
                    ).awaitAll()
                }

            assertEquals(1, results.count { it is AppResult.Success })
            assertEquals(
                1,
                results.count { it is AppResult.Error && it.errorType == PasswordResetError.INVALID_TOKEN },
                "the losing concurrent confirmation must see the consumed token, not update the password again",
            )
        }
    }

    @Test
    fun `concurrent password reset requests leave exactly one active token`() {
        skipIfNoDocker()

        val repository = PausingFirstUserLockRepository(UserRepositoryI())
        withRealDatabase(userRepository = repository) { harness, _ ->
            harness.service
                .createUser(
                    password = "Str0ngPassphrase1",
                    email = "reset-request-race@example.com",
                    name = "Reset Request Race",
                    role = UserRole.BUYER,
                ).fold(onSuccess = {}, onError = { fail("registration failed: $it") })
            repository.arm()

            val jobs =
                coroutineScope {
                    val requests =
                        listOf(
                            async(Dispatchers.IO) {
                                harness.service.requestPasswordReset("reset-request-race@example.com")
                            },
                            async(Dispatchers.IO) {
                                harness.service.requestPasswordReset("reset-request-race@example.com")
                            },
                        )
                    try {
                        assertTrue(repository.firstLockAcquired.awaitWithinTimeout())
                        assertTrue(repository.secondLockAttempted.awaitWithinTimeout())
                    } finally {
                        repository.releaseFirstLock.complete(Unit)
                    }
                    requests.awaitAll()
                }

            assertEquals(2, jobs.size)
            val tokens = harness.emailOutbox.passwordResetEmails.map { it.rawToken }
            assertEquals(2, tokens.size)
            val validations = tokens.map { harness.service.validatePasswordResetToken(it) }
            assertEquals(1, validations.count { it is AppResult.Success })
            assertEquals(
                1,
                validations.count { it is AppResult.Error && it.errorType == PasswordResetError.INVALID_TOKEN },
                "the newer reset request must invalidate the previous token even under concurrency",
            )
        }
    }
}

class UserEmailVerificationRealDatabaseTest : RealUserServiceDatabaseTestSupport() {
    @Test
    fun `concurrent verification requests consume one token exactly once`() {
        skipIfNoDocker()

        val repository = CoordinatedVerificationReadRepository(UserRepositoryI(), firstReadParties = 2)
        withRealDatabase(userRepository = repository) { harness, _ ->
            harness.service
                .createUser(
                    password = "Str0ngPassphrase1",
                    email = "verify-race@example.com",
                    name = "Verify Race",
                    role = UserRole.BUYER,
                ).fold(onSuccess = {}, onError = { fail("registration failed: $it") })
            val token =
                harness.emailOutbox.verificationEmails
                    .single()
                    .rawToken

            val results =
                coroutineScope {
                    listOf(
                        async(Dispatchers.IO) { harness.service.verifyEmail(token) },
                        async(Dispatchers.IO) { harness.service.verifyEmail(token) },
                    ).awaitAll()
                }

            assertEquals(1, results.count { it is AppResult.Success })
            assertEquals(
                1,
                results.count { it is AppResult.Error && it.errorType == VerifyEmailError.INVALID_TOKEN },
            )
            assertTrue(
                assertNotNull(
                    harness.tx.transactional {
                        harness.userRepository.findUser("verify-race@example.com")
                    },
                ).isEmailVerified,
            )
        }
    }

    @Test
    fun `concurrent verification resends leave only one newly issued token active`() {
        skipIfNoDocker()

        val repository = PausingFirstUserLockRepository(UserRepositoryI())
        withRealDatabase(userRepository = repository) { harness, _ ->
            harness.service
                .createUser(
                    password = "Str0ngPassphrase1",
                    email = "resend-race@example.com",
                    name = "Resend Race",
                    role = UserRole.BUYER,
                ).fold(onSuccess = {}, onError = { fail("registration failed: $it") })
            val userId =
                assertNotNull(
                    harness.tx.transactional { harness.userRepository.findUser("resend-race@example.com") },
                ).id
            repository.arm()

            val requests =
                coroutineScope {
                    val resends =
                        listOf(
                            async(Dispatchers.IO) { harness.service.resendVerificationEmail(userId) },
                            async(Dispatchers.IO) { harness.service.resendVerificationEmail(userId) },
                        )
                    try {
                        assertTrue(repository.firstLockAcquired.awaitWithinTimeout())
                        assertTrue(repository.secondLockAttempted.awaitWithinTimeout())
                    } finally {
                        repository.releaseFirstLock.complete(Unit)
                    }
                    resends.awaitAll()
                }

            assertEquals(2, requests.count { it is AppResult.Success })
            val tokens = harness.emailOutbox.verificationEmails.map { it.rawToken }
            val states =
                tokens.map { rawToken ->
                    harness.tx.transactional {
                        harness.userRepository
                            .findVerificationToken(
                                harness.tokenManager.hashTokenForStorage(rawToken),
                            )?.used
                    }
                }
            assertEquals(true, states.first(), "registration's original verification token must be invalidated")
            assertEquals(1, states.count { it == false }, "only the latest resend token should remain usable")
        }
    }
}

class UserSessionRealDatabaseTest : RealUserServiceDatabaseTestSupport() {
    @Test
    fun `logout all serializes with refresh and revokes a token issued by the in-flight refresh`() {
        skipIfNoDocker()

        val repository = PausingFirstUserLockRepository(UserRepositoryI())
        withRealDatabase(userRepository = repository) { harness, _ ->
            harness.service
                .createUser(
                    password = "Str0ngPassphrase1",
                    email = "logout-refresh-race@example.com",
                    name = "Logout Refresh Race",
                    role = UserRole.BUYER,
                ).fold(onSuccess = {}, onError = { fail("registration failed: $it") })
            val userId =
                assertNotNull(
                    harness.tx.transactional { harness.userRepository.findUser("logout-refresh-race@example.com") },
                ).id
            val session =
                harness.service
                    .signInUser("Str0ngPassphrase1", "logout-refresh-race@example.com")
                    .fold(onSuccess = { it }, onError = { fail("initial sign-in failed: $it") })
            repository.arm()

            val rotated =
                coroutineScope {
                    val refresh = async(Dispatchers.IO) { harness.service.refreshToken(session.refreshToken) }
                    try {
                        assertTrue(repository.firstLockAcquired.awaitWithinTimeout())
                        val logout = async(Dispatchers.IO) { harness.sessionService.logoutAllSessions(userId) }
                        assertTrue(repository.secondLockAttempted.awaitWithinTimeout())
                        repository.releaseFirstLock.complete(Unit)
                        val rotatedToken =
                            refresh.await().fold(
                                onSuccess = { it },
                                onError = { fail("refresh should commit before logout-all: $it") },
                            )
                        logout.await()
                        rotatedToken
                    } finally {
                        repository.releaseFirstLock.complete(Unit)
                    }
                }

            assertEquals(
                RefreshError.TOKEN_REUSE_DETECTED,
                harness.service.refreshToken(rotated.refreshToken).fold(
                    onSuccess = { fail("logout-all must revoke the refresh token created by the concurrent rotation") },
                    onError = { it },
                ),
            )
        }
    }
}

private class ConcurrentPasswordCheckGate(
    private val delegate: PasswordService,
    private val gatedPassword: String,
    parties: Int,
) : PasswordService {
    val bothChecksStarted = CountDownLatch(parties)
    val releaseChecks = CountDownLatch(1)

    override suspend fun hashPassword(password: String): String = delegate.hashPassword(password)

    override suspend fun verifyPassword(
        password: String,
        hash: String,
    ): Boolean {
        if (password == gatedPassword) {
            bothChecksStarted.countDown()
            check(releaseChecks.await(10, TimeUnit.SECONDS)) { "password-check gate timed out" }
        }
        return delegate.verifyPassword(password, hash)
    }
}

private class ConcurrentPasswordHashGate(
    private val delegate: PasswordService,
    parties: Int,
) : PasswordService {
    val bothHashesStarted = CountDownLatch(parties)
    val releaseHashes = CountDownLatch(1)

    override suspend fun hashPassword(password: String): String {
        bothHashesStarted.countDown()
        check(releaseHashes.await(10, TimeUnit.SECONDS)) { "password-hash gate timed out" }
        return delegate.hashPassword(password)
    }

    override suspend fun verifyPassword(
        password: String,
        hash: String,
    ): Boolean = delegate.verifyPassword(password, hash)
}

private class FailingPasswordUpdateRepository(
    delegate: UserRepository,
) : UserRepository by delegate {
    override suspend fun updatePassword(
        userId: Int,
        newPasswordHash: String,
    ) = error("simulated password update failure")
}

private class CoordinatedPasswordResetReadRepository(
    private val delegate: UserRepository,
    private val firstReadParties: Int,
) : UserRepository by delegate {
    private val firstReads = AtomicInteger()
    private val firstReadsBarrier = SuspendBarrier(firstReadParties)

    override suspend fun findPasswordResetToken(token: String) =
        delegate.findPasswordResetToken(token).also {
            if (firstReads.incrementAndGet() <= firstReadParties) {
                firstReadsBarrier.await()
            }
        }
}

private class CoordinatedVerificationReadRepository(
    private val delegate: UserRepository,
    private val firstReadParties: Int,
) : UserRepository by delegate {
    private val firstReads = AtomicInteger()
    private val firstReadsBarrier = SuspendBarrier(firstReadParties)

    override suspend fun findVerificationToken(token: String) =
        delegate.findVerificationToken(token).also {
            if (firstReads.incrementAndGet() <= firstReadParties) {
                firstReadsBarrier.await()
            }
        }
}

private class PausingFirstUserLockRepository(
    private val delegate: UserRepository,
) : UserRepository by delegate {
    private val lockCalls = AtomicInteger()
    private val armed = AtomicBoolean()
    val firstLockAcquired = CompletableDeferred<Unit>()
    val secondLockAttempted = CompletableDeferred<Unit>()
    val releaseFirstLock = CompletableDeferred<Unit>()

    fun arm() {
        armed.set(true)
    }

    override suspend fun findUserByIdForUpdate(userId: Int): User? {
        if (!armed.get()) return delegate.findUserByIdForUpdate(userId)

        val call = lockCalls.incrementAndGet()
        if (call == 2) secondLockAttempted.complete(Unit)
        val user = delegate.findUserByIdForUpdate(userId)
        if (call == 1) {
            firstLockAcquired.complete(Unit)
            withTimeout(10_000) { releaseFirstLock.await() }
        }
        return user
    }
}

private suspend fun CompletableDeferred<Unit>.awaitWithinTimeout(): Boolean =
    withTimeout(10_000) {
        await()
        true
    }

private class SuspendBarrier(
    private val parties: Int,
) {
    private val arrivals = AtomicInteger()
    private val open = CompletableDeferred<Unit>()

    suspend fun await() {
        if (arrivals.incrementAndGet() == parties) open.complete(Unit)
        withTimeout(10_000) { open.await() }
    }
}
