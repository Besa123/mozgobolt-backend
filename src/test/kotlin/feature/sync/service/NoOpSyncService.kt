package com.shelflife.feature.sync.service

import com.shelflife.feature.sync.domain.SyncService
import com.shelflife.feature.sync.domain.model.SyncEntityType
import com.shelflife.feature.sync.domain.model.SyncOperation
import com.shelflife.feature.sync.domain.model.SyncPage

/**
 * Shared across product/storageLocation/pantryEntry's fake-repository unit tests: none of those
 * services' own behavior depends on sync at all — `recordChange` is a side effect they trigger,
 * never read back — so a no-op stand-in is all any of them need.
 */
class NoOpSyncService : SyncService {
    override suspend fun recordChange(
        userId: Int,
        entityType: SyncEntityType,
        entityId: Int,
        operation: SyncOperation,
        originDeviceId: String?,
    ) = Unit

    override suspend fun changesSince(
        userId: Int,
        cursor: Long,
        limit: Int?,
    ): SyncPage = SyncPage(events = emptyList(), nextCursor = cursor)
}
