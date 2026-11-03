/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.deviceInstallation.data.repository

import com.mozgobolt.feature.deviceInstallation.data.database.DeviceInstallationsTable
import com.mozgobolt.feature.deviceInstallation.data.mapper.toDeviceInstallation
import com.mozgobolt.feature.deviceInstallation.domain.DeviceInstallationRepository
import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallation
import com.mozgobolt.feature.deviceInstallation.domain.model.DevicePlatform
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

class DeviceInstallationRepositoryI : DeviceInstallationRepository {
    override suspend fun register(
        userId: Int,
        installationId: String,
        platform: DevicePlatform,
        now: Instant,
    ): DeviceInstallation {
        val installationIdHash = hashInstallationId(installationId)
        DeviceInstallationsTable.upsert(
            DeviceInstallationsTable.installationIdHash,
            onUpdate = { update ->
                update[DeviceInstallationsTable.installationId] = installationId
                update[DeviceInstallationsTable.platform] = platform
                update[DeviceInstallationsTable.userId] = userId
                update[DeviceInstallationsTable.updatedAt] = now
                update[DeviceInstallationsTable.registrationVersion] =
                    DeviceInstallationsTable.registrationVersion + 1L
            },
        ) {
            it[DeviceInstallationsTable.userId] = userId
            it[DeviceInstallationsTable.installationId] = installationId
            it[DeviceInstallationsTable.installationIdHash] = installationIdHash
            it[DeviceInstallationsTable.platform] = platform
            it[DeviceInstallationsTable.createdAt] = now
            it[DeviceInstallationsTable.updatedAt] = now
            it[DeviceInstallationsTable.registrationVersion] = 1L
        }

        val registered =
            DeviceInstallationsTable
                .selectAll()
                .where { DeviceInstallationsTable.installationIdHash eq installationIdHash }
                .single()
                .toDeviceInstallation()
        check(registered.installationId == installationId) { "Installation ID hash collision" }
        return registered
    }

    override suspend fun findAllForUser(userId: Int): List<DeviceInstallation> =
        DeviceInstallationsTable
            .selectAll()
            .where { DeviceInstallationsTable.userId eq userId }
            .orderBy(DeviceInstallationsTable.id to SortOrder.ASC)
            .map { it.toDeviceInstallation() }

    override suspend fun deleteForUser(
        userId: Int,
        installationId: String,
    ): Boolean {
        val installationIdHash = hashInstallationId(installationId)
        return DeviceInstallationsTable.deleteWhere {
            (DeviceInstallationsTable.userId eq userId) and
                (DeviceInstallationsTable.installationIdHash eq installationIdHash) and
                (DeviceInstallationsTable.installationId eq installationId)
        } > 0
    }

    override suspend fun deleteIfCurrent(installation: DeviceInstallation): Boolean {
        val installationIdHash = hashInstallationId(installation.installationId)
        return DeviceInstallationsTable.deleteWhere {
            (DeviceInstallationsTable.userId eq installation.userId) and
                (DeviceInstallationsTable.installationIdHash eq installationIdHash) and
                (DeviceInstallationsTable.installationId eq installation.installationId) and
                (DeviceInstallationsTable.registrationVersion eq installation.registrationVersion)
        } > 0
    }

    private fun hashInstallationId(installationId: String): String =
        HexFormat.of().formatHex(
            MessageDigest
                .getInstance("SHA-256")
                .digest(installationId.toByteArray(StandardCharsets.UTF_8)),
        )
}
