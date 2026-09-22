package com.mozgobolt.core.data.idempotency

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

object IdempotencyKeysTable : Table("idempotency_keys") {
    val key = varchar("key", 64)
    val requestFingerprint = varchar("request_fingerprint", 64).default("")
    val statusCode = integer("status_code").nullable()
    val responseBody = text("response_body").default("")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(key)
}
