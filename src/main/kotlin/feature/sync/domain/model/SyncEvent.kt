package com.mozgobolt.feature.sync.domain.model

import java.time.Instant

data class SyncEvent(
    val id: Long,
    val userId: Int,
    val entityType: SyncEntityType,
    val entityId: Int,
    val operation: SyncOperation,
    val occurredAt: Instant,
)
