package com.mozgobolt.feature.proximityNotification.data.mapper

import com.mozgobolt.feature.proximityNotification.data.database.ProximityNotificationEntity
import com.mozgobolt.feature.proximityNotification.domain.model.ProximityNotificationRecord

fun ProximityNotificationEntity.toProximityNotificationRecord() =
    ProximityNotificationRecord(
        id = id.value,
        buyerUserId = buyerUserId,
        savedLocationId = savedLocationId,
        vehicleId = vehicleId,
        currentlyInside = currentlyInside,
        lastNotifiedAt = lastNotifiedAt,
    )
