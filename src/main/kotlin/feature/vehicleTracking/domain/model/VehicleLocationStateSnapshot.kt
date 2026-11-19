/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.domain.model

import java.time.Instant

/** Current assignment facts needed to reconcile a process-local live-location cache. */
data class ActiveVehicleLocationAssignmentSnapshot(
    val vehicleId: Int,
    val assignmentId: Int,
    val vendorUserId: Int,
    val startedAt: Instant,
    val sharesLocationWithCustomers: Boolean,
)

/**
 * Persisted state used to seed or repair one process's volatile live-location cache.
 * [latestLocations] contains at most one recent, consented position per active assignment.
 */
data class VehicleLocationStateSnapshot(
    val activeAssignments: List<ActiveVehicleLocationAssignmentSnapshot>,
    val latestLocations: List<VehicleLocationUpdate>,
)
