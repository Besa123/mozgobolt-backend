package com.mozgobolt.feature.deviceInstallation.data.database

import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallationConstraints
import com.mozgobolt.feature.deviceInstallation.domain.model.DevicePlatform
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp

data object DeviceInstallationsTable : IntIdTable("device_installations") {
    val userId = integer("user_id")
    val installationId = varchar("installation_id", DeviceInstallationConstraints.INSTALLATION_ID_MAX_LENGTH)
    val platform = enumerationByName<DevicePlatform>("platform", DeviceInstallationConstraints.PLATFORM_COLUMN_LENGTH)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

class DeviceInstallationEntity(
    id: EntityID<Int>,
) : IntEntity(id) {
    var userId by DeviceInstallationsTable.userId
    var installationId by DeviceInstallationsTable.installationId
    var platform by DeviceInstallationsTable.platform
    var createdAt by DeviceInstallationsTable.createdAt
    var updatedAt by DeviceInstallationsTable.updatedAt

    companion object : IntEntityClass<DeviceInstallationEntity>(DeviceInstallationsTable)
}
