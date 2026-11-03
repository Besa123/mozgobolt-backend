/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.data.repository

import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.user.data.database.UserEmailOutboxTable
import com.mozgobolt.feature.user.data.database.UsersTable
import com.mozgobolt.feature.user.data.security.UserEmailTokenCipherI
import com.mozgobolt.feature.user.domain.model.UserEmailType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserEmailOutboxRepositoryFailureRealDatabaseTest {
    private val cipher = UserEmailTokenCipherI("real-database-outbox-test-pepper")

    @Test
    fun `permanently failed messages stay recorded and are never reclaimed`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val userRepository = UserRepositoryI()
            val outboxRepository = UserEmailOutboxRepositoryI(cipher)
            val tokenHash = "permanent-state-hash"
            val expiresAt = Instant.now().plusSeconds(3600)

            tx.transactional {
                userRepository.createPasswordResetToken(1, tokenHash, expiresAt)
                outboxRepository.enqueue(
                    1,
                    UserEmailType.PASSWORD_RESET,
                    tokenHash,
                    "permanent-state-raw-token",
                    expiresAt,
                )
            }

            val failedAt = Instant.now().plusSeconds(1)
            val deliveries =
                tx.transactional {
                    outboxRepository.claimBatch(failedAt, failedAt.plusSeconds(30), limit = 1)
                }
            val delivery = deliveries.single()
            val wrongLeaseWasMarkedFailed =
                tx.transactional {
                    outboxRepository.markFailed(
                        delivery.id,
                        "wrong-lease",
                        failedAt,
                        "PermanentEmailDeliveryException",
                    )
                }
            val currentLeaseWasMarkedFailed =
                tx.transactional {
                    outboxRepository.markFailed(
                        delivery.id,
                        delivery.leaseToken,
                        failedAt,
                        "PermanentEmailDeliveryException",
                    )
                }
            assertFalse(wrongLeaseWasMarkedFailed)
            assertTrue(currentLeaseWasMarkedFailed)
            assertFalse(tx.transactional { outboxRepository.isCurrent(delivery.id, delivery.leaseToken, failedAt) })
            assertTrue(
                tx.transactional {
                    outboxRepository
                        .claimBatch(
                            now = failedAt.plusSeconds(600),
                            leaseUntil = failedAt.plusSeconds(630),
                            limit = 1,
                        ).isEmpty()
                },
            )
        }
    }

    @Test
    fun `discard only removes the message with the current lease`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val userRepository = UserRepositoryI()
            val outboxRepository = UserEmailOutboxRepositoryI(cipher)
            val tokenHash = "discard-state-hash"
            val expiresAt = Instant.now().plusSeconds(3600)

            tx.transactional {
                userRepository.createVerificationToken(1, tokenHash, expiresAt)
                outboxRepository.enqueue(
                    1,
                    UserEmailType.VERIFICATION,
                    tokenHash,
                    "discard-state-raw-token",
                    expiresAt,
                )
            }

            val claimTime = Instant.now().plusSeconds(1)
            val deliveries =
                tx.transactional {
                    outboxRepository.claimBatch(claimTime, claimTime.plusSeconds(30), limit = 1)
                }
            val delivery = deliveries.single()
            val wrongLeaseDiscarded =
                tx.transactional {
                    outboxRepository.discard(delivery.id, "wrong-lease")
                }
            val currentLeaseDiscarded =
                tx.transactional {
                    outboxRepository.discard(delivery.id, delivery.leaseToken)
                }

            assertFalse(wrongLeaseDiscarded)
            assertTrue(currentLeaseDiscarded)
            assertEquals(0, tx.transactional { UserEmailOutboxTable.selectAll().count() })
        }
    }

    @Test
    fun `expired and consumed tokens are removed without being claimed`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val userRepository = UserRepositoryI()
            val outboxRepository = UserEmailOutboxRepositoryI(cipher)
            val now = Instant.now()
            val expiredHash = "expired-verification-hash"
            val usedHash = "used-verification-hash"
            val expiredAt = now.minusSeconds(1)
            val validUntil = now.plusSeconds(3600)

            tx.transactional {
                userRepository.createVerificationToken(1, expiredHash, expiredAt)
                userRepository.createVerificationToken(1, usedHash, validUntil)
                val usedTokenId = checkNotNull(userRepository.findVerificationToken(usedHash)).id
                userRepository.markTokenUsed(usedTokenId)
                outboxRepository.enqueue(
                    1,
                    UserEmailType.VERIFICATION,
                    expiredHash,
                    "expired-raw-token",
                    expiredAt,
                )
                outboxRepository.enqueue(
                    1,
                    UserEmailType.VERIFICATION,
                    usedHash,
                    "used-raw-token",
                    validUntil,
                )
            }

            val claimedMessages =
                tx.transactional {
                    outboxRepository.claimBatch(
                        now = now.plusSeconds(1),
                        leaseUntil = now.plusSeconds(61),
                        limit = 10,
                    )
                }
            assertTrue(claimedMessages.isEmpty())
            assertEquals(0, tx.transactional { UserEmailOutboxTable.selectAll().count() })
        }
    }

    @Test
    fun `user deletion cascades queued email messages`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val userRepository = UserRepositoryI()
            val outboxRepository = UserEmailOutboxRepositoryI(cipher)
            val expiresAt = Instant.now().plusSeconds(3600)

            tx.transactional {
                userRepository.createVerificationToken(1, "delete-user-token-hash", expiresAt)
                outboxRepository.enqueue(
                    1,
                    UserEmailType.VERIFICATION,
                    "delete-user-token-hash",
                    "delete-user-raw-token",
                    expiresAt,
                )
            }
            assertEquals(1, tx.transactional { UserEmailOutboxTable.selectAll().count() })

            assertEquals(1, tx.transactional { UsersTable.deleteWhere { UsersTable.id eq 1 } })
            assertEquals(0, tx.transactional { UserEmailOutboxTable.selectAll().count() })
        }
    }

    @Test
    fun `token and outbox writes roll back atomically`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val userRepository = UserRepositoryI()
            val outboxRepository = UserEmailOutboxRepositoryI(cipher)
            val tokenHash = "rolled-back-token-hash"
            val expiresAt = Instant.now().plusSeconds(3600)

            var rollbackWasObserved = false
            try {
                tx.transactional {
                    userRepository.createVerificationToken(1, tokenHash, expiresAt)
                    outboxRepository.enqueue(
                        1,
                        UserEmailType.VERIFICATION,
                        tokenHash,
                        "rolled-back-raw-token",
                        expiresAt,
                    )
                    error("force transaction rollback")
                }
            } catch (exception: IllegalStateException) {
                rollbackWasObserved = exception.message == "force transaction rollback"
            }

            assertTrue(rollbackWasObserved)
            assertNull(tx.transactional { userRepository.findVerificationToken(tokenHash) })
            assertEquals(0, tx.transactional { UserEmailOutboxTable.selectAll().count() })
        }
    }
}
