package com.mozgobolt.feature.deviceInstallation.data.repository

import com.mozgobolt.feature.deviceInstallation.data.database.DeviceInstallationEntity
import com.mozgobolt.feature.deviceInstallation.data.database.DeviceInstallationsTable
import com.mozgobolt.feature.deviceInstallation.data.mapper.toDeviceInstallation
import com.mozgobolt.feature.deviceInstallation.domain.DeviceInstallationRepository
import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallation
import com.mozgobolt.feature.deviceInstallation.domain.model.DevicePlatform
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

class DeviceInstallationRepositoryI : DeviceInstallationRepository {
    // insertIgnore (Postgres `ON CONFLICT DO NOTHING`), same reasoning as
    // CompanyMembershipRepositoryI.addIfAbsent: a unique-violation aborts the whole transaction,
    // so this must never raise the error in the first place. Re-registering an existing
    // (userId, installationId) pair is the expected common case (app start), not a rare race —
    // the follow-up UPDATE has a tiny, accepted window where a concurrent register/delete could
    // interleave, the same low-stakes tolerance already documented for the company's last-admin
    // guard elsewhere in this codebase.
    override suspend fun register(
        userId: Int,
        installationId: String,
        platform: DevicePlatform,
        now: Instant,
    ): DeviceInstallation {
        val insertedCount =
            DeviceInstallationsTable
                .insertIgnore {
                    it[DeviceInstallationsTable.userId] = userId
                    it[DeviceInstallationsTable.installationId] = installationId
                    it[DeviceInstallationsTable.platform] = platform
                    it[DeviceInstallationsTable.createdAt] = now
                    it[DeviceInstallationsTable.updatedAt] = now
                }.insertedCount

        if (insertedCount == 0) {
            DeviceInstallationsTable.update(
                where = {
                    (DeviceInstallationsTable.userId eq userId) and
                        (DeviceInstallationsTable.installationId eq installationId)
                },
            ) {
                it[DeviceInstallationsTable.platform] = platform
                it[DeviceInstallationsTable.updatedAt] = now
            }
        }

        return checkNotNull(findEntity(userId, installationId)) {
            "device_installations row for (userId=$userId) must exist immediately after insertIgnore/update"
        }.toDeviceInstallation()
    }

    override suspend fun findAllForUser(userId: Int): List<DeviceInstallation> =
        DeviceInstallationEntity
            .find { DeviceInstallationsTable.userId eq userId }
            .map { it.toDeviceInstallation() }

    override suspend fun findByInstallationId(installationId: String): DeviceInstallation? =
        DeviceInstallationEntity
            .find { DeviceInstallationsTable.installationId eq installationId }
            .firstOrNull()
            ?.toDeviceInstallation()

    override suspend fun deleteForUser(
        userId: Int,
        installationId: String,
    ): Boolean {
        val entity = findEntity(userId, installationId) ?: return false
        entity.delete()
        return true
    }

    override suspend fun deleteByInstallationId(installationId: String) {
        DeviceInstallationEntity
            .find { DeviceInstallationsTable.installationId eq installationId }
            .forEach { it.delete() }
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
