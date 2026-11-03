/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.service

import com.mozgobolt.core.data.email.PermanentEmailDeliveryException
import com.mozgobolt.core.data.email.TransientEmailDeliveryException
import com.mozgobolt.core.domain.email.EmailService
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.feature.user.data.security.UserEmailTokenCipherI
import com.mozgobolt.feature.user.domain.EmailTokenCipher
import com.mozgobolt.feature.user.domain.UserEmailOutboxRepository
import com.mozgobolt.feature.user.domain.model.UserEmailOutboxDelivery
import com.mozgobolt.feature.user.domain.model.UserEmailType
import com.mozgobolt.feature.user.domain.model.userEmailOutboxEncryptionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val START_TIME = Instant.parse("2026-01-01T10:00:00Z")

private class MutableClock(
    var current: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current

    fun advanceSeconds(seconds: Long) {
        current = current.plusSeconds(seconds)
    }
}

private class FakeEmailOutboxRepository(
    private val cipher: EmailTokenCipher,
    private val recipients: Map<Int, String>,
) : UserEmailOutboxRepository {
    data class Message(
        val id: Long,
        val userId: Int,
        val type: UserEmailType,
        val tokenHash: String,
        val encryptedToken: String,
        val expiresAt: Instant,
        val recipient: String,
        val attemptCount: Int = 0,
        val nextAttemptAt: Instant = Instant.EPOCH,
        val leaseUntil: Instant? = null,
        val leaseToken: String? = null,
        val failedAt: Instant? = null,
        val lastFailureType: String? = null,
        val tokenCurrent: Boolean = true,
    )

    val messages = mutableListOf<Message>()
    private var nextId = 1L
    private val staleTokenHashesAfterClaim = mutableSetOf<String>()
    var onDelivered: (() -> Unit)? = null
    var failureBeforeClaim: Throwable? = null
    var failNextDeliveryAcknowledgement = false

    override suspend fun enqueue(
        userId: Int,
        type: UserEmailType,
        tokenHash: String,
        rawToken: String,
        expiresAt: Instant,
    ) {
        if (messages.any { it.type == type && it.tokenHash == tokenHash }) return
        val encrypted =
            cipher.encrypt(
                rawToken,
                userEmailOutboxEncryptionContext(userId, type, tokenHash),
            )
        messages +=
            Message(
                id = nextId++,
                userId = userId,
                type = type,
                tokenHash = tokenHash,
                encryptedToken = encrypted,
                expiresAt = expiresAt,
                recipient = checkNotNull(recipients[userId]),
            )
    }

    fun markStaleAfterNextClaim(tokenHash: String) {
        staleTokenHashesAfterClaim += tokenHash
    }

    fun replaceEncryptedToken(
        tokenHash: String,
        encryptedToken: String,
    ) {
        val message = checkNotNull(messages.find { it.tokenHash == tokenHash })
        checkNotNull(replaceMessage(message.id) { it.copy(encryptedToken = encryptedToken) })
    }

    override suspend fun cancelPending(
        userId: Int,
        type: UserEmailType,
    ) {
        messages.removeAll { it.userId == userId && it.type == type }
    }

    override suspend fun purgeExpired(now: Instant): Int {
        val before = messages.size
        messages.removeAll { it.expiresAt.isBefore(now) }
        return before - messages.size
    }

    override suspend fun claimBatch(
        now: Instant,
        leaseUntil: Instant,
        limit: Int,
    ): List<UserEmailOutboxDelivery> {
        failureBeforeClaim?.let {
            failureBeforeClaim = null
            throw it
        }
        messages.removeAll { it.expiresAt.isBefore(now) }
        val candidates =
            messages
                .filter {
                    it.failedAt == null &&
                        !it.nextAttemptAt.isAfter(now) &&
                        (it.leaseUntil == null || !it.leaseUntil.isAfter(now))
                }.sortedWith(compareBy<Message> { it.nextAttemptAt }.thenBy { it.id })
                .take(limit)
        val claimed =
            candidates.map { candidate ->
                val leaseToken = UUID.randomUUID().toString()
                val message =
                    checkNotNull(
                        replaceMessage(candidate.id) { message ->
                            message.copy(
                                attemptCount = message.attemptCount + 1,
                                leaseUntil = leaseUntil,
                                leaseToken = leaseToken,
                            )
                        },
                    )

                UserEmailOutboxDelivery(
                    id = message.id,
                    userId = message.userId,
                    recipientEmail = message.recipient,
                    type = message.type,
                    tokenHash = message.tokenHash,
                    encryptedToken = message.encryptedToken,
                    expiresAt = message.expiresAt,
                    leaseToken = checkNotNull(message.leaseToken),
                    attemptCount = message.attemptCount,
                )
            }
        claimed
            .filter { it.tokenHash in staleTokenHashesAfterClaim }
            .forEach { delivery ->
                replaceMessage(delivery.id) { it.copy(tokenCurrent = false) }
                staleTokenHashesAfterClaim.remove(delivery.tokenHash)
            }
        return claimed
    }

    override suspend fun isCurrent(
        id: Long,
        leaseToken: String,
        now: Instant,
    ): Boolean =
        messages.any {
            it.id == id &&
                it.leaseToken == leaseToken &&
                it.leaseUntil?.isAfter(now) == true &&
                it.failedAt == null &&
                it.tokenCurrent &&
                !it.expiresAt.isBefore(now)
        }

    override suspend fun markDelivered(
        id: Long,
        leaseToken: String,
    ): Boolean {
        if (failNextDeliveryAcknowledgement) {
            failNextDeliveryAcknowledgement = false
            return false
        }
        val message = messages.find { it.id == id && it.leaseToken == leaseToken } ?: return false
        messages.remove(message)
        onDelivered?.invoke()
        return true
    }

    override suspend fun discard(
        id: Long,
        leaseToken: String,
    ): Boolean {
        val message = messages.find { it.id == id && it.leaseToken == leaseToken } ?: return false
        messages.remove(message)
        return true
    }

    override suspend fun scheduleRetry(
        id: Long,
        leaseToken: String,
        nextAttemptAt: Instant,
        failureType: String,
    ): Boolean {
        val message = messages.find { it.id == id && it.leaseToken == leaseToken } ?: return false
        replaceMessage(message.id) { currentMessage ->
            currentMessage.copy(
                nextAttemptAt = nextAttemptAt,
                leaseUntil = null,
                leaseToken = null,
                lastFailureType = failureType,
            )
        }
        return true
    }

    override suspend fun markFailed(
        id: Long,
        leaseToken: String,
        failedAt: Instant,
        failureType: String,
    ): Boolean {
        val message = messages.find { it.id == id && it.leaseToken == leaseToken } ?: return false
        replaceMessage(message.id) { currentMessage ->
            currentMessage.copy(
                failedAt = failedAt,
                leaseUntil = null,
                leaseToken = null,
                lastFailureType = failureType,
            )
        }
        return true
    }

    private fun replaceMessage(
        id: Long,
        transform: (Message) -> Message,
    ): Message? {
        val index = messages.indexOfFirst { it.id == id }
        if (index < 0) return null

        return transform(messages[index]).also { messages[index] = it }
    }
}

private class RecordingEmailService : EmailService {
    data class SentEmail(
        val type: UserEmailType,
        val recipient: String,
        val token: String,
    )

    val sent = CopyOnWriteArrayList<SentEmail>()
    var failureToThrow: Throwable? = null
    private val sendCalls = AtomicInteger()
    val sendCallCount: Int
        get() = sendCalls.get()

    override suspend fun sendVerificationEmail(
        to: String,
        token: String,
    ) {
        sendCalls.incrementAndGet()
        failureToThrow?.let {
            failureToThrow = null
            throw it
        }
        sent += SentEmail(UserEmailType.VERIFICATION, to, token)
    }

    override suspend fun sendPasswordResetEmail(
        to: String,
        token: String,
    ) {
        sendCalls.incrementAndGet()
        failureToThrow?.let {
            failureToThrow = null
            throw it
        }
        sent += SentEmail(UserEmailType.PASSWORD_RESET, to, token)
    }
}

class UserEmailOutboxWorkerTest {
    private val cipher = UserEmailTokenCipherI("worker-test-password-pepper")

    @Test
    fun `worker decrypts and sends both email types then removes delivered messages`() =
        kotlinx.coroutines.runBlocking {
            val repository =
                FakeEmailOutboxRepository(
                    cipher,
                    mapOf(1 to "verify@example.test", 2 to "reset@example.test"),
                )
            repository.enqueue(
                1,
                UserEmailType.VERIFICATION,
                "hash:verify",
                "verification-token",
                START_TIME.plusSeconds(3600),
            )
            repository.enqueue(
                2,
                UserEmailType.PASSWORD_RESET,
                "hash:reset",
                "reset-token",
                START_TIME.plusSeconds(3600),
            )
            val emailService = RecordingEmailService()
            val worker = newWorker(repository, emailService, MutableClock(START_TIME))

            worker.processAvailableOnce()

            assertEquals(
                setOf(
                    RecordingEmailService.SentEmail(
                        UserEmailType.VERIFICATION,
                        "verify@example.test",
                        "verification-token",
                    ),
                    RecordingEmailService.SentEmail(
                        UserEmailType.PASSWORD_RESET,
                        "reset@example.test",
                        "reset-token",
                    ),
                ),
                emailService.sent.toSet(),
            )
            assertTrue(repository.messages.isEmpty())
        }

    @Test
    fun `transient failures schedule a durable retry and eventually deliver`() =
        kotlinx.coroutines.runBlocking {
            val repository = newRepository()
            repository.enqueue(
                1,
                UserEmailType.VERIFICATION,
                "hash:retry",
                "retry-token",
                START_TIME.plusSeconds(3600),
            )
            val clock = MutableClock(START_TIME)
            val emailService =
                RecordingEmailService().apply {
                    failureToThrow = TransientEmailDeliveryException("provider unavailable")
                }
            val worker = newWorker(repository, emailService, clock)

            worker.processAvailableOnce()

            assertEquals(1, emailService.sendCallCount)
            assertEquals(1, repository.messages.single().attemptCount)
            assertEquals(START_TIME.plusSeconds(1), repository.messages.single().nextAttemptAt)

            worker.processAvailableOnce()
            assertEquals(1, emailService.sendCallCount, "the message must wait until its backoff elapses")

            clock.advanceSeconds(1)
            worker.processAvailableOnce()

            assertEquals(2, emailService.sendCallCount)
            assertEquals("retry-token", emailService.sent.single().token)
            assertTrue(repository.messages.isEmpty())
        }

    @Test
    fun `permanent provider failures are retained but are not retried`() =
        kotlinx.coroutines.runBlocking {
            val repository = newRepository()
            repository.enqueue(
                1,
                UserEmailType.PASSWORD_RESET,
                "hash:permanent",
                "permanent-token",
                START_TIME.plusSeconds(3600),
            )
            val clock = MutableClock(START_TIME)
            val emailService =
                RecordingEmailService().apply {
                    failureToThrow = PermanentEmailDeliveryException("rejected")
                }
            val worker = newWorker(repository, emailService, clock)

            worker.processAvailableOnce()
            clock.advanceSeconds(600)
            worker.processAvailableOnce()

            assertEquals(1, emailService.sendCallCount)
            assertNotNull(repository.messages.single().failedAt)
            assertEquals("PermanentEmailDeliveryException", repository.messages.single().lastFailureType)
        }

    @Test
    fun `retry delay doubles and is capped at five minutes`() =
        kotlinx.coroutines.runBlocking {
            val repository = newRepository()
            repository.enqueue(
                1,
                UserEmailType.VERIFICATION,
                "hash:backoff",
                "backoff-token",
                START_TIME.plusSeconds(3600),
            )
            val clock = MutableClock(START_TIME)
            val emailService = RecordingEmailService()
            val worker = newWorker(repository, emailService, clock)

            for (attempt in 1..11) {
                emailService.failureToThrow = TransientEmailDeliveryException("provider unavailable")
                worker.processAvailableOnce()

                val exponentialDelay = 1L shl (attempt - 1).coerceAtMost(9)
                val expectedDelay = exponentialDelay.coerceAtMost(300)
                assertEquals(attempt, repository.messages.single().attemptCount)
                assertEquals(clock.instant().plusSeconds(expectedDelay), repository.messages.single().nextAttemptAt)
                clock.advanceSeconds(expectedDelay)
            }

            assertEquals(11, emailService.sendCallCount)
            assertTrue(emailService.sent.isEmpty())
            assertNull(repository.messages.single().failedAt)
        }

    @Test
    fun `a superseded token is discarded before delivery`() =
        kotlinx.coroutines.runBlocking {
            val repository = newRepository()
            repository.enqueue(
                1,
                UserEmailType.VERIFICATION,
                "hash:superseded",
                "old-token",
                START_TIME.plusSeconds(3600),
            )
            repository.cancelPending(1, UserEmailType.VERIFICATION)
            val emailService = RecordingEmailService()
            val worker = newWorker(repository, emailService, MutableClock(START_TIME))

            worker.processAvailableOnce()

            assertTrue(emailService.sent.isEmpty())
            assertTrue(repository.messages.isEmpty())
        }

    @Test
    fun `a token consumed after claim is rechecked and discarded before delivery`() =
        kotlinx.coroutines.runBlocking {
            val repository = newRepository()
            repository.enqueue(
                1,
                UserEmailType.VERIFICATION,
                "hash:consumed-after-claim",
                "consumed-token",
                START_TIME.plusSeconds(3600),
            )
            repository.markStaleAfterNextClaim("hash:consumed-after-claim")
            val emailService = RecordingEmailService()
            val worker = newWorker(repository, emailService, MutableClock(START_TIME))

            worker.processAvailableOnce()

            assertEquals(0, emailService.sendCallCount)
            assertTrue(emailService.sent.isEmpty())
            assertTrue(repository.messages.isEmpty())
        }

    @Test
    fun `corrupt ciphertext is marked permanently failed without sending`() =
        kotlinx.coroutines.runBlocking {
            val repository = newRepository()
            repository.enqueue(
                1,
                UserEmailType.PASSWORD_RESET,
                "hash:corrupt",
                "corrupt-token",
                START_TIME.plusSeconds(3600),
            )
            repository.replaceEncryptedToken("hash:corrupt", "not-valid-base64!")
            val emailService = RecordingEmailService()
            val worker = newWorker(repository, emailService, MutableClock(START_TIME))

            worker.processAvailableOnce()

            assertEquals(0, emailService.sendCallCount)
            assertNotNull(repository.messages.single().failedAt)
            assertEquals("IllegalArgumentException", repository.messages.single().lastFailureType)
        }

    @Test
    fun `authenticated ciphertext tampering is marked permanently failed`() =
        kotlinx.coroutines.runBlocking {
            val repository = newRepository()
            repository.enqueue(
                1,
                UserEmailType.PASSWORD_RESET,
                "hash:tampered",
                "tampered-token",
                START_TIME.plusSeconds(3600),
            )
            val encryptedBytes = Base64.getUrlDecoder().decode(repository.messages.single().encryptedToken)
            encryptedBytes[encryptedBytes.lastIndex] = (encryptedBytes.last().toInt() xor 1).toByte()
            repository.replaceEncryptedToken(
                "hash:tampered",
                Base64.getUrlEncoder().withoutPadding().encodeToString(encryptedBytes),
            )
            val emailService = RecordingEmailService()
            val worker = newWorker(repository, emailService, MutableClock(START_TIME))

            worker.processAvailableOnce()

            assertEquals(0, emailService.sendCallCount)
            assertNotNull(repository.messages.single().failedAt)
            assertEquals("AEADBadTagException", repository.messages.single().lastFailureType)
        }

    @Test
    fun `retry is discarded when its next attempt would be after token expiration`() =
        kotlinx.coroutines.runBlocking {
            val repository = newRepository()
            repository.enqueue(
                1,
                UserEmailType.VERIFICATION,
                "hash:expiring-retry",
                "expiring-token",
                START_TIME.plusMillis(500),
            )
            val emailService =
                RecordingEmailService().apply {
                    failureToThrow = TransientEmailDeliveryException("provider unavailable")
                }
            val worker = newWorker(repository, emailService, MutableClock(START_TIME))

            worker.processAvailableOnce()

            assertEquals(1, emailService.sendCallCount)
            assertTrue(repository.messages.isEmpty())
        }

    @Test
    fun `cancellation preserves the lease for safe recovery after worker restart`() =
        kotlinx.coroutines.runBlocking {
            val repository = newRepository()
            repository.enqueue(
                1,
                UserEmailType.VERIFICATION,
                "hash:cancellation",
                "cancel-token",
                START_TIME.plusSeconds(3600),
            )
            val clock = MutableClock(START_TIME)
            val emailService =
                RecordingEmailService().apply {
                    failureToThrow = CancellationException("shutdown")
                }
            val worker = newWorker(repository, emailService, clock)

            assertFailsWith<CancellationException> { worker.processAvailableOnce() }
            assertNotNull(repository.messages.single().leaseUntil)

            clock.advanceSeconds(301)
            worker.processAvailableOnce()

            assertEquals(2, emailService.sendCallCount)
            assertEquals("cancel-token", emailService.sent.single().token)
            assertTrue(repository.messages.isEmpty())
        }

    @Test
    fun `lost delivery acknowledgement is retried at least once after the lease expires`() =
        kotlinx.coroutines.runBlocking {
            val repository = newRepository()
            repository.enqueue(
                1,
                UserEmailType.VERIFICATION,
                "hash:lost-acknowledgement",
                "same-single-use-token",
                START_TIME.plusSeconds(3600),
            )
            repository.failNextDeliveryAcknowledgement = true
            val clock = MutableClock(START_TIME)
            val emailService = RecordingEmailService()
            val worker = newWorker(repository, emailService, clock)

            worker.processAvailableOnce()

            assertEquals(1, emailService.sendCallCount)
            assertEquals(1, repository.messages.size)
            clock.advanceSeconds(301)
            worker.processAvailableOnce()

            assertEquals(2, emailService.sendCallCount)
            assertEquals(
                listOf("same-single-use-token", "same-single-use-token"),
                emailService.sent.map { it.token },
            )
            assertTrue(repository.messages.isEmpty())
        }

    @Test
    fun `worker start is idempotent and stop safely cancels polling`() =
        kotlinx.coroutines.runBlocking {
            val repository = newRepository()
            repository.failureBeforeClaim = IOException("temporary database outage")
            repository.enqueue(
                1,
                UserEmailType.VERIFICATION,
                "hash:lifecycle",
                "lifecycle-token",
                START_TIME.plusSeconds(3600),
            )
            val delivered = CompletableDeferred<Unit>()
            repository.onDelivered = { delivered.complete(Unit) }
            val emailService = RecordingEmailService()
            val worker =
                newWorker(
                    repository = repository,
                    emailService = emailService,
                    clock = MutableClock(START_TIME),
                    pollInterval = 10.milliseconds,
                )

            worker.start()
            worker.start()
            withTimeout(3.seconds) { delivered.await() }
            worker.stop()
            worker.stop()

            assertEquals(1, emailService.sendCallCount)
            assertTrue(repository.messages.isEmpty())
        }

    private fun newRepository() = FakeEmailOutboxRepository(cipher, mapOf(1 to "user@example.test"))

    private fun newWorker(
        repository: FakeEmailOutboxRepository,
        emailService: RecordingEmailService,
        clock: Clock,
        pollInterval: Duration = 2.seconds,
    ) = UserEmailOutboxWorker(
        repository = repository,
        tokenCipher = cipher,
        emailService = emailService,
        tx = NoopTransactionalRunner(),
        backgroundScope = ApplicationBackgroundScope(),
        clock = clock,
        pollInterval = pollInterval,
    )
}
