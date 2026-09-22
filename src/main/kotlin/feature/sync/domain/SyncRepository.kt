package com.mozgobolt.feature.sync.domain

import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncEvent
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.sync.domain.model.SyncPage

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
