package com.mozgobolt.feature.vehicle.data.repository

import com.mozgobolt.feature.vehicle.data.database.VehicleEntity
import com.mozgobolt.feature.vehicle.data.database.VehiclesTable
import com.mozgobolt.feature.vehicle.data.mapper.toVehicle
import com.mozgobolt.feature.vehicle.domain.VehicleRepository
import com.mozgobolt.feature.vehicle.domain.model.Vehicle
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

class VehicleRepositoryI : VehicleRepository {
    // insertIgnore (Postgres `ON CONFLICT DO NOTHING`), not a caught unique-constraint exception:
    // a violation aborts the *entire* Postgres transaction, so this must never raise the error in
    // the first place — same proven pattern as CompanyMembershipRepositoryI.addIfAbsent.
    override suspend fun create(
        companyId: Int,
        label: String,
        licensePlate: String,
        pictureUrl: String?,
    ): Vehicle? {
        val insertedCount =
            VehiclesTable
                .insertIgnore {
                    it[VehiclesTable.companyId] = companyId
                    it[VehiclesTable.label] = label
                    it[VehiclesTable.licensePlate] = licensePlate
                    it[VehiclesTable.pictureUrl] = pictureUrl
                    it[VehiclesTable.createdAt] = Instant.now()
                }.insertedCount

        return insertedCount.takeIf { it > 0 }?.let { findByCompanyAndPlate(companyId, licensePlate) }
    }

    override suspend fun findById(vehicleId: Int): Vehicle? =
        VehicleEntity
            .find { VehiclesTable.id eq vehicleId }
            .firstOrNull()
            ?.toVehicle()

    override suspend fun findAllByCompany(companyId: Int): List<Vehicle> =
        VehicleEntity
            .find { (VehiclesTable.companyId eq companyId) and VehiclesTable.archivedAt.isNull() }
            .map { it.toVehicle() }

    override suspend fun findByCompanyAndPlate(
        companyId: Int,
        licensePlate: String,
    ): Vehicle? =
        VehicleEntity
            .find { (VehiclesTable.companyId eq companyId) and (VehiclesTable.licensePlate eq licensePlate) }
            .firstOrNull()
            ?.toVehicle()

    override suspend fun updateDetails(
        vehicleId: Int,
        licensePlate: String,
        pictureUrl: String?,
    ): Vehicle? {
        val updated =
            VehiclesTable.update(where = { VehiclesTable.id eq vehicleId }) {
                it[VehiclesTable.licensePlate] = licensePlate
                it[VehiclesTable.pictureUrl] = pictureUrl
            }
        return updated.takeIf { it > 0 }?.let { findById(vehicleId) }
    }

    override suspend fun archive(vehicleId: Int): Boolean {
        val updated =
            VehiclesTable.update(
                where = { (VehiclesTable.id eq vehicleId) and VehiclesTable.archivedAt.isNull() },
            ) {
                it[VehiclesTable.archivedAt] = Instant.now()
            }
        return updated > 0
    }

    override suspend fun updatePictureUrl(
        vehicleId: Int,
        pictureUrl: String?,
    ): Vehicle? {
        val updated =
            VehiclesTable.update(where = { VehiclesTable.id eq vehicleId }) {
                it[VehiclesTable.pictureUrl] = pictureUrl
            }
        return updated.takeIf { it > 0 }?.let { findById(vehicleId) }
    }
}
