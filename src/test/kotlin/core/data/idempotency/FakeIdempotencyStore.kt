package com.mozgobolt.core.data.idempotency

import java.time.Instant

class FakeIdempotencyStore : IdempotencyStore {
    private val records = mutableMapOf<String, IdempotencyRecord>()
    var acquireLockCallCount: Int = 0
        private set

    override suspend fun find(key: String): IdempotencyRecord? = records[key]

    override suspend fun acquireLock(
        key: String,
        fingerprint: String,
    ): Boolean {
        acquireLockCallCount++
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

    override suspend fun complete(
        key: String,
        statusCode: Int,
        body: String,
    ) {
        val existing = records[key] ?: return
        records[key] = existing.copy(statusCode = statusCode, responseBody = body)
    }

    override suspend fun deleteStaleLock(key: String) {
        records.remove(key)
    }

    fun seed(record: IdempotencyRecord) {
        records[record.key] = record
    }
}
