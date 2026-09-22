package com.mozgobolt.feature.proximityNotification.domain.model

import java.time.Instant

data class ProximityNotificationRecord(
    val id: Int,
    val buyerUserId: Int,
    val savedLocationId: Int,
    val vehicleId: Int,
    val currentlyInside: Boolean,
    val lastNotifiedAt: Instant,
)
