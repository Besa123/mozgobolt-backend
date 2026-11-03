/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.deviceInstallation.domain

import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallation
import com.mozgobolt.feature.deviceInstallation.domain.model.DevicePlatform
import java.time.Instant

class FakeDeviceInstallationRepository : DeviceInstallationRepository {
    private val installationsById = mutableMapOf<Int, DeviceInstallation>()
    private var nextId = 1

    fun seed(
        userId: Int,
        installationId: String,
        platform: DevicePlatform = DevicePlatform.ANDROID,
    ): DeviceInstallation {
        val installation =
            DeviceInstallation(
                id = nextId++,
                userId = userId,
                installationId = installationId,
                platform = platform,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                registrationVersion = 1L,
            )
        installationsById[installation.id] = installation
        return installation
    }

    override suspend fun register(
        userId: Int,
        installationId: String,
        platform: DevicePlatform,
        now: Instant,
    ): DeviceInstallation {
        val existing = installationsById.values.find { it.installationId == installationId }
        val saved =
            existing?.copy(
                userId = userId,
                platform = platform,
                updatedAt = now,
                registrationVersion = existing.registrationVersion + 1L,
            ) ?: DeviceInstallation(
                id = nextId++,
                userId = userId,
                installationId = installationId,
                platform = platform,
                createdAt = now,
                updatedAt = now,
                registrationVersion = 1L,
            )
        installationsById[saved.id] = saved
        return saved
    }

    override suspend fun findAllForUser(userId: Int): List<DeviceInstallation> =
        installationsById.values.filter { it.userId == userId }

    override suspend fun deleteForUser(
        userId: Int,
        installationId: String,
    ): Boolean {
        val match =
            installationsById.values.find { it.userId == userId && it.installationId == installationId }
                ?: return false
        installationsById.remove(match.id)
        return true
    }

    override suspend fun deleteIfCurrent(installation: DeviceInstallation): Boolean {
        val current = installationsById[installation.id] ?: return false
        if (current.userId != installation.userId) return false
        if (current.installationId != installation.installationId) return false
        if (current.registrationVersion != installation.registrationVersion) return false
        installationsById.remove(installation.id)
        return true
    }
}
