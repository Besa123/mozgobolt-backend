/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleAssignment.domain

import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment

/** Narrow assignment operations used by session and account lifecycle features. */
interface VehicleAssignmentSessionTerminator {
    /** Finds a vendor's active assignment, if one exists. */
    suspend fun findActiveForVendor(vendorUserId: Int): VehicleAssignment?

    /** Ends a vendor's active assignment and returns the ended record, if any. */
    suspend fun endActiveAssignmentForVendor(vendorUserId: Int): VehicleAssignment?

    /** Notifies live-location consumers that the supplied assignments have ended. */
    fun notifyEndedAssignments(assignments: List<VehicleAssignment>)
}
