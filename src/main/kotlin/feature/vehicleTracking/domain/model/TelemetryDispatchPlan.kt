/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.domain.model

/** Accepted telemetry whose in-memory and notification effects must wait until the write commits. */
data class TelemetryDispatchPlan(
    val vehicleId: Int,
    val companyId: Int?,
    val sharesLocationWithCustomers: Boolean,
    val updates: List<VehicleLocationUpdate>,
)
