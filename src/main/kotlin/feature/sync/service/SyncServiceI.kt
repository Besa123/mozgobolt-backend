/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.sync.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.feature.sync.domain.SyncRepository
import com.mozgobolt.feature.sync.domain.SyncService
import com.mozgobolt.feature.sync.domain.model.SyncChange
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.sync.domain.model.SyncPage

private const val DEFAULT_PAGE_SIZE = 200
private const val MAX_PAGE_SIZE = 500

class SyncServiceI(
    private val syncRepository: SyncRepository,
    private val tx: TransactionalRunner,
) : SyncService {
    override suspend fun recordChange(
        userId: Int,
        entityType: SyncEntityType,
        entityId: Int,
        operation: SyncOperation,
        originDeviceId: String?,
    ) {
        recordChanges(listOf(SyncChange(userId, entityType, entityId, operation, originDeviceId)))
    }

    override suspend fun recordChanges(changes: List<SyncChange>) {
        if (changes.isEmpty()) return

        tx.transactional {
            changes
                .sortedBy(SyncChange::userId)
                .forEach { change ->
                    syncRepository.record(
                        userId = change.userId,
                        entityType = change.entityType,
                        entityId = change.entityId,
                        operation = change.operation,
                        originDeviceId = change.originDeviceId,
                    )
                }
        }
    }

    override suspend fun changesSince(
        userId: Int,
        cursor: Long,
        limit: Int?,
    ): SyncPage {
        val clampedLimit = (limit ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)

        return tx.transactional { syncRepository.findSince(userId, cursor, clampedLimit) }
    }
}
