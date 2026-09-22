package com.mozgobolt.feature.savedLocation.data.mapper

import com.mozgobolt.feature.savedLocation.data.database.UserSavedLocationEntity
import com.mozgobolt.feature.savedLocation.domain.model.UserSavedLocation

fun UserSavedLocationEntity.toUserSavedLocation() =
    UserSavedLocation(
        id = id.value,
        userId = userId,
        label = label,
        latitude = latitude.toDouble(),
        longitude = longitude.toDouble(),
        radiusKm = radiusKm.toDouble(),
        createdAt = createdAt,
    )
