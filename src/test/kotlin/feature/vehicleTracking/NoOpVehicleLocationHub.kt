package com.mozgobolt.feature.vehicleTracking

import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLiveEvent
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Shared across harnesses whose tests don't care what happens to the location hub at all — every
 * operation is a genuine no-op rather than an error stub, since `markOffline` is called on every
 * successful unlink/end and must not blow up here.
 *
 * Not for a test that asserts *how* the hub was used — those need a purpose-built spy that fails
 * loudly on any unexpected call (see `VehicleAssignmentServiceITest.FakeVehicleLocationHub` and
 * `VehicleTrackingServiceITest.FakeVehicleLocationHub`, each narrowly recording only the one thing
 * its own test cares about); collapsing those into this shared no-op would silently swallow calls
 * they're deliberately built to catch.
 */
class NoOpVehicleLocationHub : VehicleLocationHub {
    override fun publish(update: VehicleLocationUpdate) = Unit

    override fun markOffline(vehicleId: Int) = Unit

    override fun lastKnownLocation(vehicleId: Int): VehicleLocationUpdate? = null

    override fun subscribeNear(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): Flow<VehicleLiveEvent> = emptyFlow()

    override fun subscribeAll(): Flow<VehicleLiveEvent> = emptyFlow()

    override fun snapshotNear(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): List<VehicleLocationUpdate> = emptyList()

    override fun snapshotAll(): List<VehicleLocationUpdate> = emptyList()
}
