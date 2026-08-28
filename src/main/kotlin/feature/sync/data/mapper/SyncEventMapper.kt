package com.shelflife.feature.sync.data.mapper

import com.shelflife.feature.sync.data.database.SyncEventsTable
import com.shelflife.feature.sync.domain.model.SyncEntityType
import com.shelflife.feature.sync.domain.model.SyncEvent
import com.shelflife.feature.sync.domain.model.SyncOperation
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toSyncEvent() =
    SyncEvent(
        id = this[SyncEventsTable.id],
        userId = this[SyncEventsTable.userId],
        entityType = SyncEntityType.valueOf(this[SyncEventsTable.entityType]),
        entityId = this[SyncEventsTable.entityId],
        operation = SyncOperation.valueOf(this[SyncEventsTable.operation]),
        occurredAt = this[SyncEventsTable.occurredAt],
    )
