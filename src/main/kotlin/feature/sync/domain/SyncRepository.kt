package com.shelflife.feature.sync.domain

import com.shelflife.feature.sync.domain.model.SyncEntityType
import com.shelflife.feature.sync.domain.model.SyncEvent
import com.shelflife.feature.sync.domain.model.SyncOperation
import com.shelflife.feature.sync.domain.model.SyncPage

interface SyncRepository {
    suspend fun record(
        userId: Int,
        entityType: SyncEntityType,
        entityId: Int,
        operation: SyncOperation,
        originDeviceId: String? = null,
    ): SyncEvent

    suspend fun findSince(
        userId: Int,
        cursor: Long,
        limit: Int,
    ): SyncPage
}
