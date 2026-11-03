/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.domain

import com.mozgobolt.feature.user.domain.model.UserEmailOutboxDelivery
import com.mozgobolt.feature.user.domain.model.UserEmailType
import java.time.Instant

/** Transactional persistence operations for queued verification and password-reset emails. */
interface UserEmailOutboxRepository {
    /** Call within the transaction that creates the corresponding verification/reset token. */
    suspend fun enqueue(
        userId: Int,
        type: UserEmailType,
        tokenHash: String,
        rawToken: String,
        expiresAt: Instant,
    )

    /** Removes pending messages for tokens that the same transaction is superseding or consuming. */
    suspend fun cancelPending(
        userId: Int,
        type: UserEmailType,
    )

    /** Removes expired outbox records and returns the number removed. */
    suspend fun purgeExpired(now: Instant): Int

    /** Claims up to [limit] pending messages until [leaseUntil]. */
    suspend fun claimBatch(
        now: Instant,
        leaseUntil: Instant,
        limit: Int,
    ): List<UserEmailOutboxDelivery>

    /** Rechecks the lease and token state immediately before the external send. */
    suspend fun isCurrent(
        id: Long,
        leaseToken: String,
        now: Instant,
    ): Boolean

    /** Marks a leased message as delivered if the lease still belongs to [leaseToken]. */
    suspend fun markDelivered(
        id: Long,
        leaseToken: String,
    ): Boolean

    /** Discards a leased message if the lease still belongs to [leaseToken]. */
    suspend fun discard(
        id: Long,
        leaseToken: String,
    ): Boolean

    /** Schedules a leased message for retry if the lease still belongs to [leaseToken]. */
    suspend fun scheduleRetry(
        id: Long,
        leaseToken: String,
        nextAttemptAt: Instant,
        failureType: String,
    ): Boolean

    /** Marks a leased message as permanently failed if the lease is still current. */
    suspend fun markFailed(
        id: Long,
        leaseToken: String,
        failedAt: Instant,
        failureType: String,
    ): Boolean
}
