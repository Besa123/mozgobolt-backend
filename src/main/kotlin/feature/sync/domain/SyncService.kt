/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.sync.domain

import com.mozgobolt.feature.sync.domain.model.SyncChange
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.sync.domain.model.SyncPage

/** Records durable synchronization changes and reads pages for a user's devices. */
interface SyncService {
    /** Records a mutation in the caller's write transaction. */
    suspend fun recordChange(
        userId: Int,
        entityType: SyncEntityType,
        entityId: Int,
        operation: SyncOperation,
        originDeviceId: String? = null,
    )

    /**
     * Records all changes atomically, acquiring per-user sync locks in ascending user-id order.
     * This is required when one business transaction affects multiple users.
     */
    suspend fun recordChanges(changes: List<SyncChange>)

    /** Returns changes after [cursor], bounded by the optional [limit]. */
    suspend fun changesSince(
        userId: Int,
        cursor: Long,
        limit: Int?,
    ): SyncPage
}
