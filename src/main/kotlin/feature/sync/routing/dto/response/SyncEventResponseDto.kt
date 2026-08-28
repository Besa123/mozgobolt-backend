package com.shelflife.feature.sync.routing.dto.response

import com.shelflife.feature.sync.domain.model.SyncEvent
import com.shelflife.feature.sync.domain.model.SyncPage
import kotlinx.serialization.Serializable

@Serializable
data class SyncEventResponseDto(
    val id: Long,
    val entityType: String,
    val entityId: Int,
    val operation: String,
    val occurredAt: String,
)

fun SyncEvent.toResponseDto() =
    SyncEventResponseDto(
        id = id,
        entityType = entityType.name,
        entityId = entityId,
        operation = operation.name,
        occurredAt = occurredAt.toString(),
    )

@Serializable
data class SyncPageResponseDto(
    val events: List<SyncEventResponseDto>,
    val nextCursor: Long,
)

fun SyncPage.toResponseDto() =
    SyncPageResponseDto(
        events = events.map { it.toResponseDto() },
        nextCursor = nextCursor,
    )
