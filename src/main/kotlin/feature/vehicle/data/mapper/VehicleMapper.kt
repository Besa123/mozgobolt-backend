/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicle.data.mapper

import com.mozgobolt.feature.vehicle.data.database.VehicleEntity
import com.mozgobolt.feature.vehicle.data.database.VehiclesTable
import com.mozgobolt.feature.vehicle.domain.model.Vehicle
import org.jetbrains.exposed.v1.core.ResultRow

fun VehicleEntity.toVehicle() =
    Vehicle(
        id = id.value,
        companyId = companyId,
        label = label,
        licensePlate = licensePlate,
        pictureUrl = pictureUrl,
        createdAt = createdAt,
        archivedAt = archivedAt,
        pictureStorageKey = pictureStorageKey,
    )

fun ResultRow.toVehicle() =
    Vehicle(
        id = this[VehiclesTable.id].value,
        companyId = this[VehiclesTable.companyId],
        label = this[VehiclesTable.label],
        licensePlate = this[VehiclesTable.licensePlate],
        pictureUrl = this[VehiclesTable.pictureUrl],
        createdAt = this[VehiclesTable.createdAt],
        archivedAt = this[VehiclesTable.archivedAt],
        pictureStorageKey = this[VehiclesTable.pictureStorageKey],
    )
