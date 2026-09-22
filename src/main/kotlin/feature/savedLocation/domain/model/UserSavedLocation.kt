package com.mozgobolt.feature.savedLocation.domain.model

import java.time.Instant

data class UserSavedLocation(
    val id: Int,
    val userId: Int,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val radiusKm: Double,
    val createdAt: Instant,
)
