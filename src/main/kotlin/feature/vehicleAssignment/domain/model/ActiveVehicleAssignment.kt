/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleAssignment.domain.model

data class ActiveVehicleAssignment(
    val id: Int,
    val vehicleId: Int,
    val companyId: Int,
    val vehicleLabel: String,
    val licensePlate: String,
    val sharesLocationWithCustomers: Boolean = false,
)
