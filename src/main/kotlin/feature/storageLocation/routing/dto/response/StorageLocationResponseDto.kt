package com.shelflife.feature.storageLocation.routing.dto.response

import com.shelflife.feature.storageLocation.domain.model.StorageLocation
import kotlinx.serialization.Serializable

@Serializable
data class StorageLocationResponseDto(
    val id: Int,
    val name: String,
)

fun StorageLocation.toResponseDto() =
    StorageLocationResponseDto(
        id = id,
        name = name,
    )
