/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.data.database

import com.mozgobolt.feature.user.domain.model.UserEmailType
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp

data object UserEmailOutboxTable : LongIdTable("user_email_outbox") {
    val userId =
        integer("user_id")
            .references(
                UsersTable.id,
                onDelete = ReferenceOption.CASCADE,
                onUpdate = ReferenceOption.RESTRICT,
            ).index()
    val type = enumerationByName<UserEmailType>("email_type", 20)
    val tokenHash = varchar("token_hash", 64)
    val encryptedToken = text("encrypted_token")
    val expiresAt = timestamp("expires_at")
    val createdAt = timestamp("created_at")
    val attemptCount = integer("attempt_count").default(0)
    val nextAttemptAt = timestamp("next_attempt_at")
    val leaseUntil = timestamp("lease_until").nullable()
    val leaseToken = varchar("lease_token", 36).nullable()
    val failedAt = timestamp("failed_at").nullable()
    val lastFailureType = varchar("last_failure_type", 100).nullable()

    init {
        uniqueIndex(type, tokenHash)
        index(false, failedAt, nextAttemptAt, leaseUntil)
        index(false, expiresAt)
    }
}
