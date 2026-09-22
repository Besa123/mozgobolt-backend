package com.mozgobolt.feature.vehicleTracking.domain.model

/**
 * What a live subscriber ([com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub]'s
 * `subscribeNear`/`subscribeAll`) can receive — a position, or a signal that a vehicle just went
 * offline (its driving session ended) and should be removed from whatever map is watching it.
 * Snapshots (that same hub's `snapshotNear`/`snapshotAll`) stay [VehicleLocationUpdate]-only — an
 * offline vehicle is simply absent from a fresh snapshot, so only the *live* path needs to
 * represent "it just left" as an explicit event.
 */
sealed interface VehicleLiveEvent {
    data class Position(
        val update: VehicleLocationUpdate,
    ) : VehicleLiveEvent

    data class Offline(
        val vehicleId: Int,
    ) : VehicleLiveEvent
}
