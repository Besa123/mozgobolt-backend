package com.mozgobolt.feature.savedLocation.routing.dto.response

import com.mozgobolt.feature.savedLocation.domain.model.UserSavedLocation
import kotlinx.serialization.Serializable

@Serializable
data class SavedLocationResponseDto(
    val id: Int,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val radiusKm: Double,
)

fun UserSavedLocation.toResponseDto() =
    SavedLocationResponseDto(
        id = id,
        label = label,
        latitude = latitude,
        longitude = longitude,
        radiusKm = radiusKm,
    )
