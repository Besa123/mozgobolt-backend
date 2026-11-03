/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.data.repository

import com.mozgobolt.feature.user.data.database.EmailVerificationTokensTable
import com.mozgobolt.feature.user.data.database.PasswordResetTokensTable
import com.mozgobolt.feature.user.data.database.UserEmailOutboxTable
import com.mozgobolt.feature.user.data.database.UsersTable
import com.mozgobolt.feature.user.domain.EmailTokenCipher
import com.mozgobolt.feature.user.domain.UserEmailOutboxRepository
import com.mozgobolt.feature.user.domain.model.UserEmailOutboxDelivery
import com.mozgobolt.feature.user.domain.model.UserEmailType
import com.mozgobolt.feature.user.domain.model.userEmailOutboxEncryptionContext
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

class UserEmailOutboxRepositoryI(
    private val tokenCipher: EmailTokenCipher,
) : UserEmailOutboxRepository {
    override suspend fun enqueue(
        userId: Int,
        type: UserEmailType,
        tokenHash: String,
        rawToken: String,
        expiresAt: Instant,
    ) {
        val now = Instant.now()
        val encryptedToken =
            tokenCipher.encrypt(
                rawToken,
                userEmailOutboxEncryptionContext(userId, type, tokenHash),
            )

        UserEmailOutboxTable.insertIgnore {
            it[UserEmailOutboxTable.userId] = userId
            it[UserEmailOutboxTable.type] = type
            it[UserEmailOutboxTable.tokenHash] = tokenHash
            it[UserEmailOutboxTable.encryptedToken] = encryptedToken
            it[UserEmailOutboxTable.expiresAt] = expiresAt
            it[UserEmailOutboxTable.createdAt] = now
            it[UserEmailOutboxTable.attemptCount] = 0
            it[UserEmailOutboxTable.nextAttemptAt] = now
        }
    }

    override suspend fun cancelPending(
        userId: Int,
        type: UserEmailType,
    ) {
        UserEmailOutboxTable.deleteWhere {
            (UserEmailOutboxTable.userId eq userId) and (UserEmailOutboxTable.type eq type)
        }
    }

    override suspend fun purgeExpired(now: Instant): Int =
        UserEmailOutboxTable.deleteWhere { UserEmailOutboxTable.expiresAt less now }

    override suspend fun claimBatch(
        now: Instant,
        leaseUntil: Instant,
        limit: Int,
    ): List<UserEmailOutboxDelivery> {
        require(limit > 0) { "Email outbox batch limit must be positive" }
        require(leaseUntil > now) { "Email outbox lease must expire after claim time" }

        UserEmailOutboxTable.deleteWhere { UserEmailOutboxTable.expiresAt less now }

        val candidates =
            UserEmailOutboxTable
                .selectAll()
                .where {
                    UserEmailOutboxTable.failedAt.isNull() and
                        (UserEmailOutboxTable.nextAttemptAt lessEq now) and
                        (
                            UserEmailOutboxTable.leaseUntil.isNull() or
                                (UserEmailOutboxTable.leaseUntil lessEq now)
                        )
                }.orderBy(
                    UserEmailOutboxTable.nextAttemptAt to SortOrder.ASC,
                    UserEmailOutboxTable.id to SortOrder.ASC,
                ).limit(limit)
                .forUpdate()
                .toList()

        return buildList {
            candidates.forEach { row ->
                val id = row[UserEmailOutboxTable.id].value
                val userId = row[UserEmailOutboxTable.userId]
                val type = row[UserEmailOutboxTable.type]
                val tokenHash = row[UserEmailOutboxTable.tokenHash]
                val expiresAt = row[UserEmailOutboxTable.expiresAt]

                if (!isTokenCurrent(userId, type, tokenHash, now)) {
                    UserEmailOutboxTable.deleteWhere { UserEmailOutboxTable.id eq id }
                    return@forEach
                }

                val recipient =
                    UsersTable
                        .select(UsersTable.email)
                        .where { UsersTable.id eq userId }
                        .singleOrNull()
                        ?.get(UsersTable.email)
                        ?: run {
                            UserEmailOutboxTable.deleteWhere { UserEmailOutboxTable.id eq id }
                            return@forEach
                        }
                val leaseToken = UUID.randomUUID().toString()
                val attemptCount = row[UserEmailOutboxTable.attemptCount] + 1
                UserEmailOutboxTable.update(where = { UserEmailOutboxTable.id eq id }) {
                    it[UserEmailOutboxTable.attemptCount] = UserEmailOutboxTable.attemptCount + 1
                    it[UserEmailOutboxTable.leaseUntil] = leaseUntil
                    it[UserEmailOutboxTable.leaseToken] = leaseToken
                }

                add(
                    UserEmailOutboxDelivery(
                        id = id,
                        userId = userId,
                        recipientEmail = recipient,
                        type = type,
                        tokenHash = tokenHash,
                        encryptedToken = row[UserEmailOutboxTable.encryptedToken],
                        expiresAt = expiresAt,
                        leaseToken = leaseToken,
                        attemptCount = attemptCount,
                    ),
                )
            }
        }
    }

    override suspend fun isCurrent(
        id: Long,
        leaseToken: String,
        now: Instant,
    ): Boolean {
        val row =
            UserEmailOutboxTable
                .selectAll()
                .where {
                    (UserEmailOutboxTable.id eq id) and
                        (UserEmailOutboxTable.leaseToken eq leaseToken) and
                        (UserEmailOutboxTable.leaseUntil greater now) and
                        UserEmailOutboxTable.failedAt.isNull()
                }.singleOrNull()
                ?: return false

        return isTokenCurrent(
            userId = row[UserEmailOutboxTable.userId],
            type = row[UserEmailOutboxTable.type],
            tokenHash = row[UserEmailOutboxTable.tokenHash],
            now = now,
        )
    }

    override suspend fun markDelivered(
        id: Long,
        leaseToken: String,
    ): Boolean = deleteClaimed(id, leaseToken)

    override suspend fun discard(
        id: Long,
        leaseToken: String,
    ): Boolean = deleteClaimed(id, leaseToken)

    override suspend fun scheduleRetry(
        id: Long,
        leaseToken: String,
        nextAttemptAt: Instant,
        failureType: String,
    ): Boolean {
        val updated =
            UserEmailOutboxTable.update(
                where = {
                    (UserEmailOutboxTable.id eq id) and
                        (UserEmailOutboxTable.leaseToken eq leaseToken) and
                        UserEmailOutboxTable.failedAt.isNull()
                },
            ) {
                it[UserEmailOutboxTable.nextAttemptAt] = nextAttemptAt
                it[UserEmailOutboxTable.leaseUntil] = null
                it[UserEmailOutboxTable.leaseToken] = null
                it[UserEmailOutboxTable.lastFailureType] = failureType
            }
        return updated > 0
    }

    override suspend fun markFailed(
        id: Long,
        leaseToken: String,
        failedAt: Instant,
        failureType: String,
    ): Boolean {
        val updated =
            UserEmailOutboxTable.update(
                where = {
                    (UserEmailOutboxTable.id eq id) and
                        (UserEmailOutboxTable.leaseToken eq leaseToken) and
                        UserEmailOutboxTable.failedAt.isNull()
                },
            ) {
                it[UserEmailOutboxTable.failedAt] = failedAt
                it[UserEmailOutboxTable.leaseUntil] = null
                it[UserEmailOutboxTable.leaseToken] = null
                it[UserEmailOutboxTable.lastFailureType] = failureType
            }
        return updated > 0
    }

    private fun deleteClaimed(
        id: Long,
        leaseToken: String,
    ): Boolean =
        UserEmailOutboxTable.deleteWhere {
            (UserEmailOutboxTable.id eq id) and (UserEmailOutboxTable.leaseToken eq leaseToken)
        } > 0

    private fun isTokenCurrent(
        userId: Int,
        type: UserEmailType,
        tokenHash: String,
        now: Instant,
    ): Boolean =
        when (type) {
            UserEmailType.VERIFICATION -> {
                EmailVerificationTokensTable
                    .selectAll()
                    .where {
                        (EmailVerificationTokensTable.userId eq userId) and
                            (EmailVerificationTokensTable.token eq tokenHash) and
                            (EmailVerificationTokensTable.used eq false) and
                            (EmailVerificationTokensTable.expiresAt greaterEq now)
                    }.limit(1)
                    .singleOrNull() != null
            }

            UserEmailType.PASSWORD_RESET -> {
                PasswordResetTokensTable
                    .selectAll()
                    .where {
                        (PasswordResetTokensTable.userId eq userId) and
                            (PasswordResetTokensTable.token eq tokenHash) and
                            (PasswordResetTokensTable.used eq false) and
                            (PasswordResetTokensTable.expiresAt greaterEq now)
                    }.limit(1)
                    .singleOrNull() != null
            }
        }
}
