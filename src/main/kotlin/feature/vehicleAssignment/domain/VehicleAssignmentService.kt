package com.mozgobolt.feature.vehicleAssignment.domain

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignmentError

interface VehicleAssignmentService {
    suspend fun link(
        vendorUserId: Int,
        vehicleId: Int,
    ): AppResult<VehicleAssignment, VehicleAssignmentError>

    suspend fun unlink(
        vendorUserId: Int,
        vehicleId: Int,
    ): AppResult<Unit, VehicleAssignmentError>

    suspend fun endActiveAssignment(
        adminUserId: Int,
        vehicleId: Int,
    ): AppResult<Unit, VehicleAssignmentError>

    /**
     * Ends [vendorUserId]'s active assignment, but only if it's for a vehicle owned by
     * [companyId] — a no-op otherwise (no active assignment at all, or one for a different
     * company).
     *
     * Trust boundary: unlike [endActiveAssignment], this performs **no permission check of its
     * own**. It's an internal cross-feature primitive for a caller (e.g. company membership
     * removal) that has *already* established the caller may do this — an admin removing a
     * member, or a member removing themselves — never expose this directly behind a route.
     */
    suspend fun endActiveAssignmentForVendorInCompany(
        vendorUserId: Int,
        companyId: Int,
    )

    /**
     * Ends the active assignment, if any, of every one of [companyId]'s vehicles — for a company
     * that's about to be deleted wholesale, so none of its vehicles are left stuck showing "still
     * driving" on the live map forever once the DB cascade removes the underlying rows.
     *
     * Same trust boundary as [endActiveAssignmentForVendorInCompany]: no permission check of its
     * own, an internal primitive for a caller (company deletion) that already validated the caller
     * may do this.
     */
    suspend fun endAllActiveAssignmentsForCompany(companyId: Int)

    suspend fun findActiveForVendor(vendorUserId: Int): VehicleAssignment?

    suspend fun findActiveForVehicle(vehicleId: Int): VehicleAssignment?
}
