package com.mozgobolt.feature.deviceInstallation.data.repository

import com.mozgobolt.feature.deviceInstallation.data.database.DeviceInstallationEntity
import com.mozgobolt.feature.deviceInstallation.data.database.DeviceInstallationsTable
import com.mozgobolt.feature.deviceInstallation.data.mapper.toDeviceInstallation
import com.mozgobolt.feature.deviceInstallation.domain.DeviceInstallationRepository
import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallation
import com.mozgobolt.feature.deviceInstallation.domain.model.DevicePlatform
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

class DeviceInstallationRepositoryI : DeviceInstallationRepository {
    override suspend fun register(
        userId: Int,
        installationId: String,
        platform: DevicePlatform,
        now: Instant,
    ): DeviceInstallation {
        DeviceInstallationsTable.upsert(
            DeviceInstallationsTable.userId,
            DeviceInstallationsTable.installationId,
            onUpdate = { update ->
                update[DeviceInstallationsTable.platform] = platform
                update[DeviceInstallationsTable.updatedAt] = now
            },
        ) {
            it[DeviceInstallationsTable.userId] = userId
            it[DeviceInstallationsTable.installationId] = installationId
            it[DeviceInstallationsTable.platform] = platform
            it[DeviceInstallationsTable.createdAt] = now
            it[DeviceInstallationsTable.updatedAt] = now
        }

        return checkNotNull(findEntity(userId, installationId)) {
            "device_installations row for (userId=$userId) must exist immediately after upsert"
        }.toDeviceInstallation()
    }

    override suspend fun findAllForUser(userId: Int): List<DeviceInstallation> =
        DeviceInstallationEntity
            .find { DeviceInstallationsTable.userId eq userId }
            .map { it.toDeviceInstallation() }

    override suspend fun deleteForUser(
        userId: Int,
        installationId: String,
    ): Boolean =
        DeviceInstallationsTable.deleteWhere {
            (DeviceInstallationsTable.userId eq userId) and
                (DeviceInstallationsTable.installationId eq installationId)
        } > 0

    override suspend fun deleteByInstallationId(installationId: String) {
        DeviceInstallationsTable.deleteWhere { DeviceInstallationsTable.installationId eq installationId }
    }

    private fun findEntity(
        userId: Int,
        installationId: String,
    ): DeviceInstallationEntity? =
        DeviceInstallationEntity
            .find {
                (DeviceInstallationsTable.userId eq userId) and
                    (DeviceInstallationsTable.installationId eq installationId)
            }.firstOrNull()
}
