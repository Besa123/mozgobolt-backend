/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.idempotency

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

internal const val IDEMPOTENCY_LOCK_TIMEOUT_SECONDS = 300L
internal val IDEMPOTENCY_RESPONSE_RETENTION = 24.hours
private const val IDEMPOTENCY_LOCK_DOMAIN = "mozgobolt-idempotency-v1:"
private const val REQUEST_IN_PROGRESS = "REQUEST_IN_PROGRESS"
private const val IDEMPOTENCY_KEY_PAYLOAD_MISMATCH = "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD"

interface IdempotencyStore {
    /** Returns a stable keyed digest; raw request fingerprints may contain passwords or tokens. */
    fun hashFingerprint(requestFingerprint: String): String

    suspend fun find(key: String): IdempotencyRecord?

    suspend fun purgeExpiredRecords(now: Instant): Int

    /**
     * Runs [handler] in the same transaction. Cacheable replay responses are stored atomically with
     * the handler's database writes. A non-cacheable result releases the key for a later retry.
     * A completed result may include a best-effort action that the caller must run only after this
     * method commits; replay results never carry that action. Cached responses expire after
     * [IDEMPOTENCY_RESPONSE_RETENTION].
     */
    suspend fun execute(
        key: String,
        fingerprint: String,
        handler: suspend () -> IdempotentResult,
    ): IdempotencyExecution
}

sealed interface IdempotencyExecution {
    data class Completed(
        val result: IdempotentResult,
        val wasReplayed: Boolean,
    ) : IdempotencyExecution

    data class Rejected(
        val statusCode: Int,
        val error: String,
    ) : IdempotencyExecution
}

data class IdempotencyRecord(
    val key: String,
    val requestFingerprint: String,
    val statusCode: Int?,
    val responseBody: String,
    val createdAt: Instant,
)

class ExposedIdempotencyStore(
    private val database: Database,
    private val fingerprintSecret: String,
    private val transactionDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : IdempotencyStore {
    init {
        require(fingerprintSecret.isNotBlank()) { "Idempotency fingerprint secret must not be blank" }
    }

    override fun hashFingerprint(requestFingerprint: String): String =
        keyedFingerprintHash(fingerprintSecret, requestFingerprint)

    override suspend fun find(key: String): IdempotencyRecord? =
        withDatabaseTransaction {
            IdempotencyKeysTable
                .selectAll()
                .where { IdempotencyKeysTable.key eq key }
                .singleOrNull()
                ?.let { row ->
                    IdempotencyRecord(
                        key = row[IdempotencyKeysTable.key],
                        requestFingerprint = row[IdempotencyKeysTable.requestFingerprint],
                        statusCode = row[IdempotencyKeysTable.statusCode],
                        responseBody = row[IdempotencyKeysTable.responseBody],
                        createdAt = row[IdempotencyKeysTable.createdAt],
                    )
                }
        }

    override suspend fun purgeExpiredRecords(now: Instant): Int =
        withDatabaseTransaction {
            val responseCutoff = now.minus(IDEMPOTENCY_RESPONSE_RETENTION.toJavaDuration())
            val staleLockCutoff = now.minusSeconds(IDEMPOTENCY_LOCK_TIMEOUT_SECONDS)
            val expiredResponses =
                IdempotencyKeysTable.statusCode.isNotNull() and
                    (IdempotencyKeysTable.createdAt less responseCutoff)
            val stalePendingRecords =
                IdempotencyKeysTable.statusCode.isNull() and
                    (IdempotencyKeysTable.createdAt less staleLockCutoff)

            IdempotencyKeysTable.deleteWhere { expiredResponses or stalePendingRecords }
        }

    override suspend fun execute(
        key: String,
        fingerprint: String,
        handler: suspend () -> IdempotentResult,
    ): IdempotencyExecution =
        withDatabaseTransaction {
            if (!tryAcquireExecutionLock(key)) {
                return@withDatabaseTransaction IdempotencyExecution.Rejected(
                    statusCode = HttpStatusCode.Conflict.value,
                    error = REQUEST_IN_PROGRESS,
                )
            }

            val existingOutcome =
                existingOutcome(
                    key = key,
                    fingerprint = fingerprint,
                    existing = findRecord(key),
                    now = Instant.now(),
                )
            if (existingOutcome != null) return@withDatabaseTransaction existingOutcome

            if (!tryInsertLock(key, fingerprint)) {
                return@withDatabaseTransaction IdempotencyExecution.Rejected(
                    statusCode = HttpStatusCode.Conflict.value,
                    error = REQUEST_IN_PROGRESS,
                )
            }

            val result = handler()
            if (result.shouldCacheResponse) {
                val updated =
                    IdempotencyKeysTable.update(
                        where = { IdempotencyKeysTable.key eq key },
                    ) {
                        it[IdempotencyKeysTable.statusCode] = result.statusCode.value
                        it[IdempotencyKeysTable.responseBody] = result.body
                    }
                check(updated == 1) { "Idempotency record disappeared before its response was stored" }
            } else {
                deleteRecord(key)
            }

            IdempotencyExecution.Completed(result = result, wasReplayed = false)
        }

    private suspend fun <T> withDatabaseTransaction(block: suspend () -> T): T =
        withContext(transactionDispatcher) {
            suspendTransaction(db = database) { block() }
        }

    private fun findRecord(key: String): IdempotencyRecord? =
        IdempotencyKeysTable
            .selectAll()
            .where { IdempotencyKeysTable.key eq key }
            .singleOrNull()
            ?.let { row ->
                IdempotencyRecord(
                    key = row[IdempotencyKeysTable.key],
                    requestFingerprint = row[IdempotencyKeysTable.requestFingerprint],
                    statusCode = row[IdempotencyKeysTable.statusCode],
                    responseBody = row[IdempotencyKeysTable.responseBody],
                    createdAt = row[IdempotencyKeysTable.createdAt],
                )
            }

    private fun existingOutcome(
        key: String,
        fingerprint: String,
        existing: IdempotencyRecord?,
        now: Instant,
    ): IdempotencyExecution? =
        existing?.let { record ->
            val completedStatusCode = record.statusCode
            val completedAtCutoff = now.minus(IDEMPOTENCY_RESPONSE_RETENTION.toJavaDuration())
            val isCompletedRecordExpired =
                completedStatusCode != null && record.createdAt.isBefore(completedAtCutoff)
            val staleCutoff = now.minusSeconds(IDEMPOTENCY_LOCK_TIMEOUT_SECONDS)

            when {
                isCompletedRecordExpired -> {
                    deleteRecord(key)
                    null
                }

                record.requestFingerprint.isNotEmpty() && record.requestFingerprint != fingerprint -> {
                    IdempotencyExecution.Rejected(
                        statusCode = HttpStatusCode.UnprocessableEntity.value,
                        error = IDEMPOTENCY_KEY_PAYLOAD_MISMATCH,
                    )
                }

                completedStatusCode != null -> {
                    IdempotencyExecution.Completed(
                        result =
                            IdempotentResult(
                                statusCode = HttpStatusCode.fromValue(completedStatusCode),
                                body = record.responseBody,
                            ),
                        wasReplayed = true,
                    )
                }

                !record.createdAt.isBefore(staleCutoff) -> {
                    IdempotencyExecution.Rejected(
                        statusCode = HttpStatusCode.Conflict.value,
                        error = REQUEST_IN_PROGRESS,
                    )
                }

                else -> {
                    deleteRecord(key)
                    null
                }
            }
        }

    private fun deleteRecord(key: String) {
        IdempotencyKeysTable.deleteWhere { IdempotencyKeysTable.key eq key }
    }

    private fun tryInsertLock(
        key: String,
        fingerprint: String,
    ): Boolean =
        IdempotencyKeysTable
            .insertIgnore {
                it[IdempotencyKeysTable.key] = key
                it[IdempotencyKeysTable.requestFingerprint] = fingerprint
                it[IdempotencyKeysTable.statusCode] = null
                it[IdempotencyKeysTable.responseBody] = ""
                it[IdempotencyKeysTable.createdAt] = Instant.now()
            }.insertedCount > 0

    @Suppress("CommandInjection")
    private fun tryAcquireExecutionLock(key: String): Boolean {
        val lockId =
            MessageDigest
                .getInstance("SHA-256")
                .digest("$IDEMPOTENCY_LOCK_DOMAIN$key".toByteArray(StandardCharsets.UTF_8))
                .let(ByteBuffer::wrap)
                .long

        val wasAcquired =
            TransactionManager
                .current()
                .exec(
                    stmt = "SELECT pg_try_advisory_xact_lock(?)",
                    args = listOf(LongColumnType() to lockId),
                    explicitStatementType = StatementType.SELECT,
                ) { resultSet ->
                    resultSet.next() && resultSet.getBoolean(1)
                }
        return wasAcquired == true
    }
}

internal fun keyedFingerprintHash(
    secret: String,
    fingerprint: String,
): String {
    if (fingerprint.isEmpty()) return ""
    require(secret.isNotBlank()) { "Idempotency fingerprint secret must not be blank" }

    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    val message = "idempotency-request-fingerprint-v1:$fingerprint".toByteArray(StandardCharsets.UTF_8)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message))
}
