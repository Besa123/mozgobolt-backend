/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.sync.routing

import com.mozgobolt.feature.sync.domain.SyncService
import com.mozgobolt.feature.sync.domain.model.SyncChange
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncEvent
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.sync.domain.model.SyncPage
import java.time.Instant

class FakeSyncService : SyncService {
    data class ChangesSinceCall(
        val userId: Int,
        val cursor: Long,
        val limit: Int?,
    )

    val recorded = mutableListOf<SyncEvent>()
    val changesSinceCalls = mutableListOf<ChangesSinceCall>()

    // Separate from `recorded`: originDeviceId is never part of the persisted SyncEvent (see
    // SyncRepository's own doc) — it only ever exists for the live-push path, so it's tracked
    // here purely for tests asserting a route actually threaded the caller's device id through.
    var lastRecordedOriginDeviceId: String? = null
        private set

    var changesSinceResult: SyncPage? = null
    private var nextId = 1L

    override suspend fun recordChange(
        userId: Int,
        entityType: SyncEntityType,
        entityId: Int,
        operation: SyncOperation,
        originDeviceId: String?,
    ) {
        lastRecordedOriginDeviceId = originDeviceId
        recorded +=
            SyncEvent(
                id = nextId++,
                userId = userId,
                entityType = entityType,
                entityId = entityId,
                operation = operation,
                occurredAt = Instant.now(),
            )
    }

    override suspend fun recordChanges(changes: List<SyncChange>) {
        changes
            .sortedBy(SyncChange::userId)
            .forEach { change ->
                recordChange(
                    change.userId,
                    change.entityType,
                    change.entityId,
                    change.operation,
                    change.originDeviceId,
                )
            }
    }

    override suspend fun changesSince(
        userId: Int,
        cursor: Long,
        limit: Int?,
    ): SyncPage {
        changesSinceCalls += ChangesSinceCall(userId, cursor, limit)
        return changesSinceResult ?: run {
            val events = recorded.filter { it.userId == userId && it.id > cursor }
            SyncPage(events = events, nextCursor = events.lastOrNull()?.id ?: cursor)
        }
    }
}
