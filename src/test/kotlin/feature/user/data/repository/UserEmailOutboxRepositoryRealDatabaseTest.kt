/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.data.repository

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.user.data.security.UserEmailTokenCipherI
import com.mozgobolt.feature.user.domain.model.UserEmailOutboxDelivery
import com.mozgobolt.feature.user.domain.model.UserEmailType
import com.mozgobolt.feature.user.domain.model.userEmailOutboxEncryptionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UserEmailOutboxRepositoryRealDatabaseTest {
    private val cipher = UserEmailTokenCipherI("real-database-outbox-test-pepper")

    @Test
    fun `expired outbox tokens are deleted even when the worker is not running`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val userRepository = UserRepositoryI()
            val outboxRepository = UserEmailOutboxRepositoryI(cipher)
            val now = Instant.now()
            val expiredAt = now.minusSeconds(1)
            val validUntil = now.plusSeconds(60)

            tx.transactional {
                userRepository.createVerificationToken(1, "expired-hash", expiredAt)
                userRepository.createVerificationToken(1, "live-hash", validUntil)
                outboxRepository.enqueue(1, UserEmailType.VERIFICATION, "expired-hash", "expired-raw", expiredAt)
                outboxRepository.enqueue(1, UserEmailType.VERIFICATION, "live-hash", "live-raw", validUntil)
            }

            val deleted = tx.transactional { outboxRepository.purgeExpired(now) }
            val claimTime = Instant.now().plusSeconds(1)

            assertEquals(1, deleted)
            assertEquals(
                listOf("live-hash"),
                tx.transactional {
                    outboxRepository
                        .claimBatch(claimTime, claimTime.plusSeconds(30), limit = 10)
                        .map { it.tokenHash }
                },
            )
        }
    }

    @Test
    fun `claim rejects invalid batch size and lease bounds before database access`() =
        kotlinx.coroutines.runBlocking {
            val repository = UserEmailOutboxRepositoryI(cipher)
            val now = Instant.now()

            var zeroLimitRejected = false
            try {
                repository.claimBatch(now, now.plusSeconds(1), limit = 0)
            } catch (expected: IllegalArgumentException) {
                zeroLimitRejected = true
            }

            var nonFutureLeaseRejected = false
            try {
                repository.claimBatch(now, now, limit = 1)
            } catch (expected: IllegalArgumentException) {
                nonFutureLeaseRejected = true
            }

            assertTrue(zeroLimitRejected)
            assertTrue(nonFutureLeaseRejected)
        }

    @Test
    fun `both email types are encrypted leased validated and removed after delivery`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val userRepository = UserRepositoryI()
            val outboxRepository = UserEmailOutboxRepositoryI(cipher)
            val createdAt = Instant.now()
            val expiresAt = createdAt.plusSeconds(3600)
            val tokens =
                mapOf(
                    "verification-hash" to ("verification-raw" to UserEmailType.VERIFICATION),
                    "reset-hash" to ("reset-raw" to UserEmailType.PASSWORD_RESET),
                )

            tx.transactional {
                userRepository.createVerificationToken(1, "verification-hash", expiresAt)
                userRepository.createPasswordResetToken(1, "reset-hash", expiresAt)
                tokens.forEach { (hash, token) ->
                    outboxRepository.enqueue(1, token.second, hash, token.first, expiresAt)
                }
            }

            val claimTime = Instant.now().plusSeconds(1)
            val leaseUntil = claimTime.plusSeconds(60)
            val deliveries =
                tx.transactional {
                    outboxRepository.claimBatch(now = claimTime, leaseUntil = leaseUntil, limit = 10)
                }

            assertEquals(2, deliveries.size)
            deliveries.forEach { delivery ->
                val (rawToken, type) = tokens.getValue(delivery.tokenHash)
                assertEquals("test-user-1@example.com", delivery.recipientEmail)
                assertEquals(type, delivery.type)
                assertNotEquals(rawToken, delivery.encryptedToken)
                assertEquals(
                    rawToken,
                    cipher.decrypt(
                        delivery.encryptedToken,
                        userEmailOutboxEncryptionContext(delivery.userId, delivery.type, delivery.tokenHash),
                    ),
                )
                val isCurrent =
                    tx.transactional {
                        outboxRepository.isCurrent(delivery.id, delivery.leaseToken, claimTime)
                    }
                assertTrue(isCurrent)
                val wrongLeaseWasRemoved =
                    tx.transactional {
                        outboxRepository.markDelivered(delivery.id, "wrong-lease")
                    }
                assertFalse(wrongLeaseWasRemoved)
                val correctLeaseWasRemoved =
                    tx.transactional {
                        outboxRepository.markDelivered(delivery.id, delivery.leaseToken)
                    }
                assertTrue(correctLeaseWasRemoved)
                val deliveredMessageIsCurrent =
                    tx.transactional {
                        outboxRepository.isCurrent(delivery.id, delivery.leaseToken, claimTime)
                    }
                assertFalse(deliveredMessageIsCurrent)
            }

            val pendingMessages =
                tx.transactional {
                    outboxRepository.claimBatch(
                        now = claimTime.plusSeconds(1),
                        leaseUntil = leaseUntil.plusSeconds(1),
                        limit = 10,
                    )
                }
            assertTrue(pendingMessages.isEmpty())
        }
    }

    @Test
    fun `concurrent workers cannot claim the same row and an expired lease can be reclaimed`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val userRepository = UserRepositoryI()
            val outboxRepository = UserEmailOutboxRepositoryI(cipher)
            val now = Instant.now()
            val expiresAt = now.plusSeconds(3600)
            val tokenHash = "concurrent-claim-hash"

            tx.transactional {
                userRepository.createVerificationToken(1, tokenHash, expiresAt)
                outboxRepository.enqueue(
                    userId = 1,
                    type = UserEmailType.VERIFICATION,
                    tokenHash = tokenHash,
                    rawToken = "concurrent-claim-token",
                    expiresAt = expiresAt,
                )
            }

            val claimTime = Instant.now().plusSeconds(1)
            val leaseUntil = claimTime.plusSeconds(30)
            val claims = concurrentlyClaim(tx, outboxRepository, claimTime, leaseUntil)
            val firstClaim = claims.flatten().single()
            assertEquals(1, claims.sumOf { it.size })
            assertEquals(1, firstClaim.attemptCount)
            val expiredLeaseIsCurrent =
                tx.transactional {
                    outboxRepository.isCurrent(firstClaim.id, firstClaim.leaseToken, leaseUntil)
                }
            assertFalse(expiredLeaseIsCurrent)

            val reclaimTime = leaseUntil.plusSeconds(1)
            val reclaimedDeliveries =
                tx.transactional {
                    outboxRepository.claimBatch(
                        now = reclaimTime,
                        leaseUntil = reclaimTime.plusSeconds(30),
                        limit = 1,
                    )
                }
            val reclaimed = reclaimedDeliveries.single()

            assertEquals(firstClaim.id, reclaimed.id)
            assertNotEquals(firstClaim.leaseToken, reclaimed.leaseToken)
            assertEquals(2, reclaimed.attemptCount)
            val firstLeaseIsCurrent =
                tx.transactional {
                    outboxRepository.isCurrent(firstClaim.id, firstClaim.leaseToken, reclaimTime)
                }
            val reclaimedLeaseIsCurrent =
                tx.transactional {
                    outboxRepository.isCurrent(reclaimed.id, reclaimed.leaseToken, reclaimTime)
                }
            val staleLeaseCanDelete =
                tx.transactional {
                    outboxRepository.markDelivered(firstClaim.id, firstClaim.leaseToken)
                }
            assertFalse(firstLeaseIsCurrent)
            assertTrue(reclaimedLeaseIsCurrent)
            assertFalse(staleLeaseCanDelete)
        }
    }

    @Test
    fun `cancellation is scoped and duplicate enqueue preserves the original encrypted token`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val userRepository = UserRepositoryI()
            val outboxRepository = UserEmailOutboxRepositoryI(cipher)
            val expiresAt = Instant.now().plusSeconds(3600)
            val expectedTokens =
                mapOf(
                    "other-user-verification" to
                        ("other-user-verification-raw" to UserEmailType.VERIFICATION),
                    "user-reset" to ("user-reset-original" to UserEmailType.PASSWORD_RESET),
                )

            tx.transactional {
                userRepository.createVerificationToken(1, "user-verification", expiresAt)
                userRepository.createVerificationToken(2, "other-user-verification", expiresAt)
                userRepository.createPasswordResetToken(1, "user-reset", expiresAt)

                outboxRepository.enqueue(
                    1,
                    UserEmailType.VERIFICATION,
                    "user-verification",
                    "canceled-verification-raw",
                    expiresAt,
                )
                outboxRepository.enqueue(
                    2,
                    UserEmailType.VERIFICATION,
                    "other-user-verification",
                    "other-user-verification-raw",
                    expiresAt,
                )
                outboxRepository.enqueue(
                    1,
                    UserEmailType.PASSWORD_RESET,
                    "user-reset",
                    "user-reset-original",
                    expiresAt,
                )
                outboxRepository.enqueue(
                    1,
                    UserEmailType.PASSWORD_RESET,
                    "user-reset",
                    "user-reset-duplicate",
                    expiresAt,
                )
                outboxRepository.cancelPending(1, UserEmailType.VERIFICATION)
            }

            val claimTime = Instant.now().plusSeconds(1)
            val deliveries =
                tx.transactional {
                    outboxRepository.claimBatch(claimTime, claimTime.plusSeconds(60), limit = 10)
                }

            assertEquals(expectedTokens.keys, deliveries.map { it.tokenHash }.toSet())
            deliveries.forEach { delivery ->
                val (expectedRawToken, expectedType) = expectedTokens.getValue(delivery.tokenHash)
                assertEquals(expectedType, delivery.type)
                assertEquals(
                    expectedRawToken,
                    cipher.decrypt(
                        delivery.encryptedToken,
                        userEmailOutboxEncryptionContext(delivery.userId, delivery.type, delivery.tokenHash),
                    ),
                )
            }
        }
    }

    @Test
    fun `retry state releases the lease and becomes claimable only after its scheduled time`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val userRepository = UserRepositoryI()
            val outboxRepository = UserEmailOutboxRepositoryI(cipher)
            val tokenHash = "retry-state-hash"
            val expiresAt = Instant.now().plusSeconds(3600)

            tx.transactional {
                userRepository.createVerificationToken(1, tokenHash, expiresAt)
                outboxRepository.enqueue(
                    1,
                    UserEmailType.VERIFICATION,
                    tokenHash,
                    "retry-state-raw-token",
                    expiresAt,
                )
            }

            val claimTime = Instant.now().plusSeconds(1)
            val firstClaims =
                tx.transactional {
                    outboxRepository.claimBatch(claimTime, claimTime.plusSeconds(30), limit = 1)
                }
            val firstClaim = firstClaims.single()
            val retryAt = claimTime.plusSeconds(60)

            val wrongLeaseWasScheduled =
                tx.transactional {
                    outboxRepository.scheduleRetry(
                        firstClaim.id,
                        "wrong-lease",
                        retryAt,
                        "TransientEmailDeliveryException",
                    )
                }
            val currentLeaseWasScheduled =
                tx.transactional {
                    outboxRepository.scheduleRetry(
                        firstClaim.id,
                        firstClaim.leaseToken,
                        retryAt,
                        "TransientEmailDeliveryException",
                    )
                }
            assertFalse(wrongLeaseWasScheduled)
            assertTrue(currentLeaseWasScheduled)

            val notYetDue =
                tx.transactional {
                    outboxRepository.claimBatch(retryAt.minusSeconds(1), retryAt.plusSeconds(30), limit = 1)
                }
            assertTrue(notYetDue.isEmpty())

            val retryClaims =
                tx.transactional {
                    outboxRepository.claimBatch(retryAt, retryAt.plusSeconds(30), limit = 1)
                }
            val retryClaim = retryClaims.single()
            assertEquals(firstClaim.id, retryClaim.id)
            assertEquals(2, retryClaim.attemptCount)
            assertNotEquals(firstClaim.leaseToken, retryClaim.leaseToken)
        }
    }

    private suspend fun concurrentlyClaim(
        tx: TransactionalRunner,
        repository: UserEmailOutboxRepositoryI,
        now: Instant,
        leaseUntil: Instant,
    ): List<List<UserEmailOutboxDelivery>> {
        val start = CyclicBarrier(2)
        return coroutineScope {
            listOf(
                async(Dispatchers.IO) {
                    start.await(10, TimeUnit.SECONDS)
                    tx.transactional { repository.claimBatch(now, leaseUntil, limit = 1) }
                },
                async(Dispatchers.IO) {
                    start.await(10, TimeUnit.SECONDS)
                    tx.transactional { repository.claimBatch(now, leaseUntil, limit = 1) }
                },
            ).map { it.await() }
        }
    }
}
