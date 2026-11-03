/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleAssignment.domain

import com.mozgobolt.feature.vehicleAssignment.domain.model.ActiveVehicleAssignment
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import java.time.Instant

/** Persistence operations for vehicle assignments and their active-shift state. */
interface VehicleAssignmentRepository {
    /** Finds the active assignment for a vendor, if one exists. */
    suspend fun findActiveForVendor(vendorUserId: Int): VehicleAssignment?

    /** Finds the active assignment and associated vehicle details for a vendor. */
    suspend fun findActiveDetailsForVendor(vendorUserId: Int): ActiveVehicleAssignment?

    /** Finds the active assignment for a vehicle, if one exists. */
    suspend fun findActiveForVehicle(vehicleId: Int): VehicleAssignment?

    /** Lists all assignments associated with a company. */
    suspend fun findAllForCompany(companyId: Int): List<VehicleAssignment>

    /** Lists active assignments older than [cutoff], limited to [limit] rows. */
    suspend fun findStaleActiveBefore(
        cutoff: Instant,
        limit: Int,
    ): List<VehicleAssignment> = emptyList()

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

    /** Starts an assignment and records the vendor's location-sharing consent time. */
    suspend fun startAssignment(
        vehicleId: Int,
        vendorUserId: Int,
        startedAt: Instant,
        locationSharingConsentedAt: Instant?,
    ): VehicleAssignment? = startAssignment(vehicleId, vendorUserId, startedAt)

    /** Updates the assignment's last telemetry-received time. */
    suspend fun recordTelemetryReceived(
        assignmentId: Int,
        vendorUserId: Int,
        receivedAt: Instant,
    ): Boolean = true

    /** Ends an assignment and reports whether an active row was updated. */
    suspend fun endAssignment(
        id: Int,
        endedAt: Instant,
    ): Boolean
}
