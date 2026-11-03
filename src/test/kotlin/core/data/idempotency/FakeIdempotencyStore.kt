/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.idempotency

import io.ktor.http.HttpStatusCode
import java.time.Instant
import kotlin.time.toJavaDuration

private const val REQUEST_IN_PROGRESS = "REQUEST_IN_PROGRESS"
private const val IDEMPOTENCY_KEY_PAYLOAD_MISMATCH = "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD"

class FakeIdempotencyStore : IdempotencyStore {
    private val records = mutableMapOf<String, IdempotencyRecord>()

    override fun hashFingerprint(requestFingerprint: String): String =
        keyedFingerprintHash("test-idempotency-fingerprint-secret", requestFingerprint)

    override suspend fun find(key: String): IdempotencyRecord? = records[key]

    override suspend fun purgeExpiredRecords(now: Instant): Int {
        val responseCutoff = now.minus(IDEMPOTENCY_RESPONSE_RETENTION.toJavaDuration())
        val staleLockCutoff = now.minusSeconds(IDEMPOTENCY_LOCK_TIMEOUT_SECONDS)
        val expiredKeys =
            records.values
                .filter { record ->
                    (record.statusCode != null && record.createdAt.isBefore(responseCutoff)) ||
                        (record.statusCode == null && record.createdAt.isBefore(staleLockCutoff))
                }.map(IdempotencyRecord::key)

        expiredKeys.forEach(records::remove)
        return expiredKeys.size
    }

    override suspend fun execute(
        key: String,
        fingerprint: String,
        handler: suspend () -> IdempotentResult,
    ): IdempotencyExecution {
        val existing = records[key]
        val existingOutcome = existingOutcome(existing, fingerprint)
        if (existingOutcome != null) return existingOutcome
        if (existing != null) records.remove(key)

        return if (acquireFakeLock(key, fingerprint)) {
            runNewHandler(key, handler)
        } else {
            IdempotencyExecution.Rejected(HttpStatusCode.Conflict.value, REQUEST_IN_PROGRESS)
        }
    }

    fun seed(record: IdempotencyRecord) {
        records[record.key] = record
    }

    private fun existingOutcome(
        record: IdempotencyRecord?,
        fingerprint: String,
    ): IdempotencyExecution? {
        val responseCutoff = Instant.now().minus(IDEMPOTENCY_RESPONSE_RETENTION.toJavaDuration())
        val staleLockCutoff = Instant.now().minusSeconds(IDEMPOTENCY_LOCK_TIMEOUT_SECONDS)
        return when {
            record == null -> {
                null
            }

            record.statusCode != null && record.createdAt.isBefore(responseCutoff) -> {
                null
            }

            record.requestFingerprint.isNotEmpty() && record.requestFingerprint != fingerprint -> {
                IdempotencyExecution.Rejected(
                    HttpStatusCode.UnprocessableEntity.value,
                    IDEMPOTENCY_KEY_PAYLOAD_MISMATCH,
                )
            }

            record.statusCode != null -> {
                IdempotencyExecution.Completed(
                    IdempotentResult(
                        HttpStatusCode.fromValue(record.statusCode),
                        record.responseBody,
                    ),
                    wasReplayed = true,
                )
            }

            !record.createdAt.isBefore(staleLockCutoff) -> {
                IdempotencyExecution.Rejected(HttpStatusCode.Conflict.value, REQUEST_IN_PROGRESS)
            }

            else -> {
                null
            }
        }
    }

    private suspend fun runNewHandler(
        key: String,
        handler: suspend () -> IdempotentResult,
    ): IdempotencyExecution.Completed {
        var completed = false
        try {
            val result = handler()
            if (result.shouldCacheResponse) {
                completeRecord(key, result.statusCode.value, result.body)
            } else {
                records.remove(key)
            }
            completed = true
            return IdempotencyExecution.Completed(result, wasReplayed = false)
        } finally {
            if (!completed) records.remove(key)
        }
    }

    private fun acquireFakeLock(
        key: String,
        fingerprint: String,
    ): Boolean {
        if (records.containsKey(key)) return false
        records[key] =
            IdempotencyRecord(
                key = key,
                requestFingerprint = fingerprint,
                statusCode = null,
                responseBody = "",
                createdAt = Instant.now(),
            )
        return true
    }

    private fun completeRecord(
        key: String,
        statusCode: Int,
        body: String,
    ) {
        val existing = records[key] ?: return
        records[key] = existing.copy(statusCode = statusCode, responseBody = body)
    }
}
