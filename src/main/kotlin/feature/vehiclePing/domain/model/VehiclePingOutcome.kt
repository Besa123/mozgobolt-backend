/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehiclePing.domain.model

/** Durable ping state plus the transient, privacy-coarsened delivery details. */
data class VehiclePingOutcome(
    val ping: VehiclePing,
    val vendorUserId: Int,
    val notification: VehiclePingNotification,
)
