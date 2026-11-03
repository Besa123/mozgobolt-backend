/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleAssignment.domain

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.vehicleAssignment.domain.model.ActiveVehicleAssignment
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignmentError
import java.time.Instant

/** Use cases for linking vendors to vehicles and ending active shifts. */
interface VehicleAssignmentService {
    /** Links a vendor to a vehicle without requesting customer location sharing. */
    suspend fun link(
        vendorUserId: Int,
        vehicleId: Int,
    ): AppResult<VehicleAssignment, VehicleAssignmentError>

    /** Links a vendor to a vehicle with the requested location-sharing consent state. */
    suspend fun link(
        vendorUserId: Int,
        vehicleId: Int,
        shareLocationWithCustomers: Boolean,
    ): AppResult<VehicleAssignment, VehicleAssignmentError> = link(vendorUserId, vehicleId)

    /** Ends the vendor's active assignment to the specified vehicle. */
    suspend fun unlink(
        vendorUserId: Int,
        vehicleId: Int,
    ): AppResult<Unit, VehicleAssignmentError>

    /** Ends a vehicle's active assignment when the caller is an authorized company admin. */
    suspend fun endActiveAssignment(
        adminUserId: Int,
        vehicleId: Int,
    ): AppResult<Unit, VehicleAssignmentError>

    /**
     * Ends [vendorUserId]'s active assignment, but only if it's for a vehicle owned by
     * [companyId] — returns the ended assignment, or `null` if there was no matching assignment.
     *
     * This deliberately does not record a sync event or notify the live hub. The caller must
     * batch the returned assignment change with any other affected entities in the same business
     * transaction, then call [notifyEndedAssignments] after commit.
     *
     * Trust boundary: unlike [endActiveAssignment], this performs **no permission check of its
     * own**. It's an internal cross-feature primitive for callers that have *already* established
     * permission — never expose this directly behind a route.
     */
    suspend fun endActiveAssignmentForVendorInCompany(
        vendorUserId: Int,
        companyId: Int,
    ): VehicleAssignment?

    /**
     * Ends the active assignment, if any, of every one of [companyId]'s vehicles — for a company
     * that's about to be deleted wholesale, so none of its vehicles are left stuck showing "still
     * driving" on the live map forever once the DB cascade removes the underlying rows.
     *
     * Same trust boundary as [endActiveAssignmentForVendorInCompany]: no permission check of its
     * own, an internal primitive for a caller (company deletion) that already validated the caller
     * may do this. Returns only assignments this call actually ended so the caller can notify the
     * live hub after commit. Use [findAllForCompany] for the complete history whose rows are
     * removed by the company cascade and need sync tombstones.
     */
    suspend fun endAllActiveAssignmentsForCompany(companyId: Int): List<VehicleAssignment>

    /** Lists the assignment history associated with [companyId]. */
    suspend fun findAllForCompany(companyId: Int): List<VehicleAssignment>

    /**
     * Clears these ended shifts from the live location hub. Call only after the transaction that
     * ended them has committed; the hub is an in-memory/relay side effect, not transactional data.
     */
    fun notifyEndedAssignments(assignments: List<VehicleAssignment>)

    /** Returns the active assignment belonging to a vendor, if one exists. */
    suspend fun findActiveForVendor(vendorUserId: Int): VehicleAssignment?

    /** Returns the active assignment plus vehicle details for a vendor, if one exists. */
    suspend fun findActiveDetailsForVendor(vendorUserId: Int): ActiveVehicleAssignment?

    /** Returns the active assignment for a vehicle, if one exists. */
    suspend fun findActiveForVehicle(vehicleId: Int): VehicleAssignment?

    /** Records when telemetry was received and reports whether it was accepted. */
    suspend fun recordTelemetryReceived(
        assignmentId: Int,
        vendorUserId: Int,
        receivedAt: Instant,
    ): Boolean = true
}
