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
        val existing = installationsById.values.find { it.userId == userId && it.installationId == installationId }
        val saved =
            existing?.copy(platform = platform, updatedAt = now)
                ?: DeviceInstallation(nextId++, userId, installationId, platform, createdAt = now, updatedAt = now)
        installationsById[saved.id] = saved
        return saved
    }

    override suspend fun findAllForUser(userId: Int): List<DeviceInstallation> =
        installationsById.values.filter { it.userId == userId }

    override suspend fun findByInstallationId(installationId: String): DeviceInstallation? =
        installationsById.values.find { it.installationId == installationId }

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

    override suspend fun deleteByInstallationId(installationId: String) {
        installationsById.values.find { it.installationId == installationId }?.let { installationsById.remove(it.id) }
    }
}
