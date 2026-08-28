package com.shelflife.feature.sync.service

import com.shelflife.core.database.TransactionalRunner
import com.shelflife.feature.sync.domain.SyncRepository
import com.shelflife.feature.sync.domain.SyncService
import com.shelflife.feature.sync.domain.model.SyncEntityType
import com.shelflife.feature.sync.domain.model.SyncOperation
import com.shelflife.feature.sync.domain.model.SyncPage

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
        tx.transactional {
            syncRepository.record(userId, entityType, entityId, operation, originDeviceId)
        }
    }

    override suspend fun changesSince(
        userId: Int,
        cursor: Long,
        limit: Int?,
    ): SyncPage {
        val clampedLimit = (limit ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)

        val events = tx.transactional { syncRepository.findSince(userId, cursor, clampedLimit) }

        return SyncPage(events = events, nextCursor = events.lastOrNull()?.id ?: cursor)
    }
}
