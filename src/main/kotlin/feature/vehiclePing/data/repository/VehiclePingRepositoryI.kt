/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehiclePing.data.repository

import com.mozgobolt.feature.vehiclePing.data.database.VehiclePingsTable
import com.mozgobolt.feature.vehiclePing.data.mapper.toVehiclePing
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingRepository
import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePing
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

class VehiclePingRepositoryI : VehiclePingRepository {
    override suspend fun lastSentAt(
        vehicleId: Int,
        buyerUserId: Int,
    ): Instant? {
        val sentAt = VehiclePingsTable.sentAt
        return VehiclePingsTable
            .select(sentAt)
            .where {
                (VehiclePingsTable.vehicleId eq vehicleId) and (VehiclePingsTable.buyerUserId eq buyerUserId)
            }.singleOrNull()
            ?.get(sentAt)
    }

    override suspend fun recordLatest(
        vehicleId: Int,
        buyerUserId: Int,
        sentAt: Instant,
    ): VehiclePing {
        VehiclePingsTable.upsert(
            VehiclePingsTable.vehicleId,
            VehiclePingsTable.buyerUserId,
            onUpdate = { update ->
                update[VehiclePingsTable.sentAt] = insertValue(VehiclePingsTable.sentAt)
            },
        ) {
            it[VehiclePingsTable.vehicleId] = vehicleId
            it[VehiclePingsTable.buyerUserId] = buyerUserId
            it[VehiclePingsTable.sentAt] = sentAt
        }

        return VehiclePingsTable
            .selectAll()
            .where {
                (VehiclePingsTable.vehicleId eq vehicleId) and (VehiclePingsTable.buyerUserId eq buyerUserId)
            }.single()
            .toVehiclePing()
    }

    override suspend fun purgeOlderThan(cutoff: Instant): Int =
        VehiclePingsTable.deleteWhere { VehiclePingsTable.sentAt less cutoff }
}
