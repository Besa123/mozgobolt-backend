package com.mozgobolt.feature.vehicle.domain.model

import java.time.Instant

data class Vehicle(
    val id: Int,
    val companyId: Int,
    val label: String,
    val licensePlate: String,
    val pictureUrl: String?,
    val createdAt: Instant,
    val archivedAt: Instant? = null,
)
