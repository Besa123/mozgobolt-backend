/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehiclePing.data.mapper

import com.mozgobolt.feature.vehiclePing.data.database.VehiclePingsTable
import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePing
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toVehiclePing() =
    VehiclePing(
        vehicleId = this[VehiclePingsTable.vehicleId],
        buyerUserId = this[VehiclePingsTable.buyerUserId],
        sentAt = this[VehiclePingsTable.sentAt],
    )
