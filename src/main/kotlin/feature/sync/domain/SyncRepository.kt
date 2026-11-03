/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.sync.domain

import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncEvent
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.sync.domain.model.SyncPage

/** Persistence operations for the synchronization change log. */
interface SyncRepository {
    /** Persists a single entity change and returns its generated event. */
    suspend fun record(
        userId: Int,
        entityType: SyncEntityType,
        entityId: Int,
        operation: SyncOperation,
        originDeviceId: String? = null,
    ): SyncEvent

    /** Loads a page of events after [cursor], returning at most [limit] rows. */
    suspend fun findSince(
        userId: Int,
        cursor: Long,
        limit: Int,
    ): SyncPage
}
