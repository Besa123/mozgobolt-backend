package com.mozgobolt.feature.vehicleTracking.domain

import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate

/** Holds telemetry between ingestion and the periodic slow-path batch write. */
interface VehicleLocationBuffer {
    fun enqueue(update: VehicleLocationUpdate)

    fun drainAll(): List<VehicleLocationUpdate>
}
