package com.mozgobolt.feature.vehicle.data.database

import com.mozgobolt.core.domain.validation.DISPLAY_NAME_MAX_LENGTH
import com.mozgobolt.feature.vehicle.domain.model.VehicleConstraints
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp

data object VehiclesTable : IntIdTable("vehicles") {
    val companyId = integer("company_id")
    val label = varchar("label", DISPLAY_NAME_MAX_LENGTH)
    val licensePlate = varchar("license_plate", VehicleConstraints.LICENSE_PLATE_MAX_LENGTH)
    val pictureUrl = text("picture_url").nullable()
    val createdAt = timestamp("created_at")
    val archivedAt = timestamp("archived_at").nullable()
}

class VehicleEntity(
    id: EntityID<Int>,
) : IntEntity(id) {
    var companyId by VehiclesTable.companyId
    var label by VehiclesTable.label
    var licensePlate by VehiclesTable.licensePlate
    var pictureUrl by VehiclesTable.pictureUrl
    var createdAt by VehiclesTable.createdAt
    var archivedAt by VehiclesTable.archivedAt

    companion object : IntEntityClass<VehicleEntity>(VehiclesTable)
}
