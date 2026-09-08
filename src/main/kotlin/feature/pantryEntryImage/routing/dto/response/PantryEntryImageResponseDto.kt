package com.shelflife.feature.pantryEntryImage.routing.dto.response

import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImage
import kotlinx.serialization.Serializable

@Serializable
data class PantryEntryImageResponseDto(
    val id: Int,
    val contentType: String,
    val sizeBytes: Int,
    val width: Int,
    val height: Int,
    val createdAt: String,
)

fun PantryEntryImage.toResponseDto() =
    PantryEntryImageResponseDto(
        id = id,
        contentType = contentType,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        createdAt = createdAt.toString(),
    )
