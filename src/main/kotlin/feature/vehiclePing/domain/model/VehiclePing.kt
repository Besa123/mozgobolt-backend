/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehiclePing.domain.model

import java.time.Instant

data class VehiclePing(
    val vehicleId: Int,
    val buyerUserId: Int,
    val sentAt: Instant,
)
