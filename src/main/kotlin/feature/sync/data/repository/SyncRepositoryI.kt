package com.mozgobolt.feature.sync.data.repository

import com.mozgobolt.feature.sync.data.database.SyncEventsTable
import com.mozgobolt.feature.sync.data.mapper.toSyncEvent
import com.mozgobolt.feature.sync.domain.SyncRepository
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncEvent
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.sync.domain.model.SyncPage
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.Instant

private val NOTIFY_PAYLOAD_TYPE = VarCharColumnType()

const val SYNC_NOTIFY_CHANNEL = "sync_events"

class SyncRepositoryI : SyncRepository {
    override suspend fun record(
        userId: Int,
        entityType: SyncEntityType,
        entityId: Int,
        operation: SyncOperation,
        originDeviceId: String?,
    ): SyncEvent {
        val occurredAt = Instant.now()

        val insertedId =
            SyncEventsTable
                .insert {
                    it[SyncEventsTable.userId] = userId
                    it[SyncEventsTable.entityType] = entityType.name
                    it[SyncEventsTable.entityId] = entityId
                    it[SyncEventsTable.operation] = operation.name
                    it[SyncEventsTable.occurredAt] = occurredAt
                }[SyncEventsTable.id]

        TransactionManager.current().exec(
            stmt = "SELECT pg_notify(?, ?)",
            args =
                listOf(
                    NOTIFY_PAYLOAD_TYPE to SYNC_NOTIFY_CHANNEL,
                    NOTIFY_PAYLOAD_TYPE to "$userId:$insertedId:${originDeviceId.orEmpty()}",
                ),
        )

        return SyncEvent(
            id = insertedId,
            userId = userId,
            entityType = entityType,
            entityId = entityId,
            operation = operation,
            occurredAt = occurredAt,
        )
    }

    override suspend fun findSince(
        userId: Int,
        cursor: Long,
        limit: Int,
    ): SyncPage {
        val rows =
            SyncEventsTable
                .selectAll()
                .where { (SyncEventsTable.userId eq userId) and (SyncEventsTable.id greater cursor) }
                .orderBy(SyncEventsTable.id to SortOrder.ASC)
                .limit(limit)
                .toList()

        val nextCursor = rows.maxOfOrNull { it[SyncEventsTable.id] } ?: cursor
        val events = rows.mapNotNull { it.toSyncEvent() }

        return SyncPage(events = events, nextCursor = nextCursor)
    }
}
