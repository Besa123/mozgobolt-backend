package com.shelflife.core.data.idempotency

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

interface IdempotencyStore {
    suspend fun find(key: String): IdempotencyRecord?

    suspend fun acquireLock(
        key: String,
        fingerprint: String,
    ): Boolean

    suspend fun complete(
        key: String,
        statusCode: Int,
        body: String,
    )

    suspend fun deleteStaleLock(key: String)
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
) : IdempotencyStore {
    override suspend fun find(key: String): IdempotencyRecord? =
        suspendTransaction(db = database) {
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

    override suspend fun acquireLock(
        key: String,
        fingerprint: String,
    ): Boolean =
        suspendTransaction(db = database) {
            IdempotencyKeysTable
                .insertIgnore {
                    it[IdempotencyKeysTable.key] = key
                    it[requestFingerprint] = fingerprint
                    it[statusCode] = null
                    it[responseBody] = ""
                    it[createdAt] = Instant.now()
                }.insertedCount > 0
        }

    override suspend fun complete(
        key: String,
        statusCode: Int,
        body: String,
    ): Unit =
        suspendTransaction(db = database) {
            IdempotencyKeysTable.update(
                where = { IdempotencyKeysTable.key eq key },
            ) {
                it[IdempotencyKeysTable.statusCode] = statusCode
                it[responseBody] = body
            }
        }

    override suspend fun deleteStaleLock(key: String): Unit =
        suspendTransaction(db = database) {
            IdempotencyKeysTable.deleteWhere { IdempotencyKeysTable.key eq key }
        }
}
