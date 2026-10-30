package com.mozgobolt.feature.vehiclePing.data.repository

import com.mozgobolt.feature.vehiclePing.data.database.VehiclePingEntity
import com.mozgobolt.feature.vehiclePing.data.database.VehiclePingsTable
import com.mozgobolt.feature.vehiclePing.data.mapper.toVehiclePing
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingRepository
import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePing
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import java.time.Instant

class VehiclePingRepositoryI : VehiclePingRepository {
    override suspend fun mostRecentSentAt(
        vehicleId: Int,
        buyerUserId: Int,
    ): Instant? {
        val sentAt = VehiclePingsTable.sentAt
        return VehiclePingsTable
            .select(sentAt)
            .where {
                (VehiclePingsTable.vehicleId eq vehicleId) and (VehiclePingsTable.buyerUserId eq buyerUserId)
            }.orderBy(sentAt to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(sentAt)
    }

    override suspend fun create(
        vehicleId: Int,
        buyerUserId: Int,
        sentAt: Instant,
    ): VehiclePing =
        VehiclePingEntity
            .new {
                this.vehicleId = vehicleId
                this.buyerUserId = buyerUserId
                this.sentAt = sentAt
            }.toVehiclePing()
}
