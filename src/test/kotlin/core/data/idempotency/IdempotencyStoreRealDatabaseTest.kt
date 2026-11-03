/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.idempotency

import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.toJavaDuration

class IdempotencyStoreRealDatabaseTest {
    @Test
    fun `a non-cacheable transient response releases its key for retry`() {
        skipIfNoDocker()

        withRealDatabase { database, _ ->
            val store = ExposedIdempotencyStore(database, "integration-test-idempotency-fingerprint-secret")
            val key = "retryable-password-check"
            val fingerprint = store.hashFingerprint("same-password-request")
            val first =
                store.execute(key, fingerprint) {
                    IdempotentResult(
                        statusCode = HttpStatusCode.ServiceUnavailable,
                        body = """{"error":"PASSWORD_CHECK_UNAVAILABLE"}""",
                        shouldCacheResponse = false,
                    )
                }

            assertEquals(
                HttpStatusCode.ServiceUnavailable,
                assertIs<IdempotencyExecution.Completed>(first).result.statusCode,
            )
            assertNull(store.find(key), "a transient dependency failure must not be replay-cached")

            val retry =
                store.execute(key, fingerprint) {
                    IdempotentResult(HttpStatusCode.Created, "created")
                }
            val completed = assertIs<IdempotencyExecution.Completed>(retry)

            assertFalse(completed.wasReplayed)
            assertEquals(HttpStatusCode.Created, completed.result.statusCode)
            assertEquals(HttpStatusCode.Created.value, store.find(key)?.statusCode)
        }
    }

    @Test
    fun `business writes and idempotency response commit or roll back together`() {
        skipIfNoDocker()

        withRealDatabase { database, tx ->
            val store = ExposedIdempotencyStore(database, "integration-test-idempotency-fingerprint-secret")
            val companyRepository = CompanyRepositoryI()
            val requestKey = "atomic-company-create"
            val fingerprint = store.hashFingerprint("create-company-request")
            val inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH)

            assertFailsWith<IllegalStateException> {
                store.execute(requestKey, fingerprint) {
                    tx.transactional {
                        companyRepository.create("Rolled back company", inviteCode)
                    }
                    error("simulated handler failure before response completion")
                }
            }

            assertNull(tx.transactional { companyRepository.findByInviteCode(inviteCode) })
            assertNull(store.find(requestKey))

            val handlerCalls = AtomicInteger()
            val completed =
                store.execute(requestKey, fingerprint) {
                    handlerCalls.incrementAndGet()
                    val company =
                        tx.transactional {
                            companyRepository.create("Committed company", inviteCode)
                        }
                    idempotentResult(HttpStatusCode.Created, mapOf("companyId" to company.id))
                }
            val execution = assertIs<IdempotencyExecution.Completed>(completed)
            assertFalse(execution.wasReplayed)
            assertEquals(HttpStatusCode.Created, execution.result.statusCode)

            val replay =
                store.execute(requestKey, fingerprint) {
                    handlerCalls.incrementAndGet()
                    error("a completed key must replay without executing the handler")
                }
            val replayedExecution = assertIs<IdempotencyExecution.Completed>(replay)
            assertTrue(replayedExecution.wasReplayed)
            assertEquals(execution.result.body, replayedExecution.result.body)
            assertEquals(1, handlerCalls.get())
            assertTrue(tx.transactional { companyRepository.findByInviteCode(inviteCode) != null })
        }
    }

    @Test
    fun `a concurrent duplicate key cannot run a second handler before the first transaction commits`() {
        skipIfNoDocker()

        withRealDatabase { database, _ ->
            val store = ExposedIdempotencyStore(database, "integration-test-idempotency-fingerprint-secret")
            val requestKey = "concurrent-idempotency-key"
            val fingerprint = store.hashFingerprint("same-request")
            val handlerStarted = CompletableDeferred<Unit>()
            val allowFirstToComplete = CompletableDeferred<Unit>()
            val handlerCalls = AtomicInteger()

            coroutineScope {
                val first =
                    async(Dispatchers.IO) {
                        store.execute(requestKey, fingerprint) {
                            handlerCalls.incrementAndGet()
                            handlerStarted.complete(Unit)
                            withTimeout(5_000) { allowFirstToComplete.await() }
                            IdempotentResult(HttpStatusCode.Accepted, "")
                        }
                    }
                withTimeout(5_000) { handlerStarted.await() }

                val second =
                    store.execute(requestKey, fingerprint) {
                        handlerCalls.incrementAndGet()
                        IdempotentResult(HttpStatusCode.Accepted, "")
                    }

                assertEquals(
                    IdempotencyExecution.Rejected(HttpStatusCode.Conflict.value, "REQUEST_IN_PROGRESS"),
                    second,
                )
                allowFirstToComplete.complete(Unit)
                assertIs<IdempotencyExecution.Completed>(first.await())
                assertEquals(1, handlerCalls.get())
            }
        }
    }

    @Test
    fun `a persisted active lock is rejected and a stale lock is reclaimed`() {
        skipIfNoDocker()

        withRealDatabase { database, _ ->
            val store = ExposedIdempotencyStore(database, "integration-test-idempotency-fingerprint-secret")
            val activeKey = "active-idempotency-lock"
            val activeFingerprint = store.hashFingerprint("active-request")
            val staleKey = "stale-idempotency-lock"
            val staleFingerprint = store.hashFingerprint("stale-request")

            suspendTransaction(db = database) {
                IdempotencyKeysTable.insert {
                    it[key] = activeKey
                    it[requestFingerprint] = activeFingerprint
                    it[statusCode] = null
                    it[responseBody] = ""
                    it[createdAt] = Instant.now()
                }
                IdempotencyKeysTable.insert {
                    it[key] = staleKey
                    it[requestFingerprint] = staleFingerprint
                    it[statusCode] = null
                    it[responseBody] = ""
                    it[createdAt] = Instant.now().minusSeconds(IDEMPOTENCY_LOCK_TIMEOUT_SECONDS + 1)
                }
            }

            assertEquals(
                IdempotencyExecution.Rejected(HttpStatusCode.Conflict.value, "REQUEST_IN_PROGRESS"),
                store.execute(activeKey, activeFingerprint) {
                    error("an active persisted lock must not run a second handler")
                },
            )

            val staleExecution =
                store.execute(staleKey, staleFingerprint) {
                    IdempotentResult(HttpStatusCode.Created, "recovered")
                }
            val completed = assertIs<IdempotencyExecution.Completed>(staleExecution)
            assertFalse(completed.wasReplayed)
            assertEquals("recovered", completed.result.body)
            assertEquals(HttpStatusCode.Created.value, store.find(staleKey)?.statusCode)
        }
    }

    @Test
    fun `an expired completed key can be reused with a new payload`() {
        skipIfNoDocker()

        withRealDatabase { database, _ ->
            val store = ExposedIdempotencyStore(database, "integration-test-idempotency-fingerprint-secret")
            val key = "expired-idempotency-key"
            val originalFingerprint = store.hashFingerprint("original-request")

            store.execute(key, originalFingerprint) {
                IdempotentResult(HttpStatusCode.Created, "original response")
            }

            suspendTransaction(db = database) {
                IdempotencyKeysTable.update({ IdempotencyKeysTable.key eq key }) {
                    it[IdempotencyKeysTable.createdAt] =
                        Instant.now().minus(IDEMPOTENCY_RESPONSE_RETENTION.toJavaDuration()).minusSeconds(1)
                }
            }

            val newFingerprint = store.hashFingerprint("request after retention")
            val handlerCalls = AtomicInteger()
            val execution =
                store.execute(key, newFingerprint) {
                    handlerCalls.incrementAndGet()
                    IdempotentResult(HttpStatusCode.OK, "fresh response")
                }

            val completed = assertIs<IdempotencyExecution.Completed>(execution)
            assertFalse(completed.wasReplayed)
            assertEquals(HttpStatusCode.OK, completed.result.statusCode)
            assertEquals("fresh response", completed.result.body)
            assertEquals(1, handlerCalls.get())
            assertEquals(newFingerprint, store.find(key)?.requestFingerprint)
        }
    }

    @Test
    fun `purging removes only completed responses past retention and stale pending locks`() {
        skipIfNoDocker()

        withRealDatabase { database, _ ->
            val store = ExposedIdempotencyStore(database, "integration-test-idempotency-fingerprint-secret")
            val now = Instant.now()
            val responseCutoff = now.minus(IDEMPOTENCY_RESPONSE_RETENTION.toJavaDuration())
            val staleLockCutoff = now.minusSeconds(IDEMPOTENCY_LOCK_TIMEOUT_SECONDS)
            val expiredCompletedKey = "expired-completed-key"
            val retainedCompletedKey = "retained-completed-key"
            val stalePendingKey = "stale-pending-key"
            val activePendingKey = "active-pending-key"

            suspendTransaction(db = database) {
                listOf(
                    IdempotencyRecord(
                        key = expiredCompletedKey,
                        requestFingerprint = store.hashFingerprint("expired"),
                        statusCode = HttpStatusCode.Created.value,
                        responseBody = "expired response",
                        createdAt = responseCutoff.minusSeconds(1),
                    ),
                    IdempotencyRecord(
                        key = retainedCompletedKey,
                        requestFingerprint = store.hashFingerprint("retained"),
                        statusCode = HttpStatusCode.Created.value,
                        responseBody = "retained response",
                        createdAt = responseCutoff.plusSeconds(1),
                    ),
                    IdempotencyRecord(
                        key = stalePendingKey,
                        requestFingerprint = store.hashFingerprint("stale"),
                        statusCode = null,
                        responseBody = "",
                        createdAt = staleLockCutoff.minusSeconds(1),
                    ),
                    IdempotencyRecord(
                        key = activePendingKey,
                        requestFingerprint = store.hashFingerprint("active"),
                        statusCode = null,
                        responseBody = "",
                        createdAt = staleLockCutoff.plusSeconds(1),
                    ),
                ).forEach { record ->
                    IdempotencyKeysTable.insert {
                        it[key] = record.key
                        it[requestFingerprint] = record.requestFingerprint
                        it[statusCode] = record.statusCode
                        it[responseBody] = record.responseBody
                        it[createdAt] = record.createdAt
                    }
                }
            }

            assertEquals(2, store.purgeExpiredRecords(now))
            assertNull(store.find(expiredCompletedKey))
            assertNull(store.find(stalePendingKey))
            assertEquals(HttpStatusCode.Created.value, store.find(retainedCompletedKey)?.statusCode)
            assertNull(store.find(activePendingKey)?.statusCode)
        }
    }
}
