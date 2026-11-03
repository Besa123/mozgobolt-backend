/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleAssignment.domain.model

import java.time.Instant

data class VehicleAssignment(
    val id: Int,
    val vehicleId: Int,
    val vendorUserId: Int,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val locationSharingConsentedAt: Instant? = null,
    val lastTelemetryAt: Instant? = null,
) {
    val isActive: Boolean
        get() = endedAt == null

    val sharesLocationWithCustomers: Boolean
        get() = locationSharingConsentedAt != null
}
