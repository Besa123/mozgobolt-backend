package com.mozgobolt.feature.sync.data.mapper

import com.mozgobolt.feature.sync.data.database.SyncEventsTable
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncEvent
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.ResultRow

private val logger = KotlinLogging.logger {}

fun ResultRow.toSyncEvent(): SyncEvent? {
    val id = this[SyncEventsTable.id]
    val entityType = this[SyncEventsTable.entityType]
    val operation = this[SyncEventsTable.operation]

    return runCatching {
        SyncEvent(
            id = id,
            userId = this[SyncEventsTable.userId],
            entityType = SyncEntityType.valueOf(entityType),
            entityId = this[SyncEventsTable.entityId],
            operation = SyncOperation.valueOf(operation),
            occurredAt = this[SyncEventsTable.occurredAt],
        )
    }.onFailure {
        logger.error(it) {
            "Skipping unreadable sync_events row id=$id (entityType=$entityType, operation=$operation)"
        }
    }.getOrNull()
}
