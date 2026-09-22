package com.mozgobolt.feature.sync.domain

import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.sync.domain.model.SyncPage

interface SyncService {
    suspend fun recordChange(
        userId: Int,
        entityType: SyncEntityType,
        entityId: Int,
        operation: SyncOperation,
        originDeviceId: String? = null,
    )

    suspend fun changesSince(
        userId: Int,
        cursor: Long,
        limit: Int?,
    ): SyncPage
}
