package com.mozgobolt.feature.vehicleAssignment.domain

import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import java.time.Instant

interface VehicleAssignmentRepository {
    suspend fun findActiveForVendor(vendorUserId: Int): VehicleAssignment?

    suspend fun findActiveForVehicle(vehicleId: Int): VehicleAssignment?

    /**
     * `null` means a concurrent request won the race first — the DB's partial unique indexes
     * (see the migration) are the actual guard; this only reports that it fired, it doesn't
     * retry. The caller (service layer) is responsible for turning that into the right domain
     * error rather than letting the underlying constraint violation surface as an exception.
     */
    suspend fun startAssignment(
        vehicleId: Int,
        vendorUserId: Int,
        startedAt: Instant,
    ): VehicleAssignment?

    suspend fun endAssignment(
        id: Int,
        endedAt: Instant,
    )
}
