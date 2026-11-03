/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.routing.dto.response

import kotlinx.serialization.Serializable

@Serializable
data class VehicleOfflineResponseDto(
    val vehicleId: Int,
)
