package com.mozgobolt.feature.deviceInstallation.domain

import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallation
import com.mozgobolt.feature.deviceInstallation.domain.model.DevicePlatform
import java.time.Instant

interface DeviceInstallationRepository {
    /** Registers a new installation, or refreshes [DeviceInstallation.updatedAt]/[DevicePlatform]
     * if this (userId, installationId) pair already exists. Never fails on a duplicate —
     * re-registering the same installation id (e.g. on every app start) is the expected, common
     * case. */
    suspend fun register(
        userId: Int,
        installationId: String,
        platform: DevicePlatform,
        now: Instant,
    ): DeviceInstallation

    suspend fun findAllForUser(userId: Int): List<DeviceInstallation>

    suspend fun findByInstallationId(installationId: String): DeviceInstallation?

    /** `true` if a matching row owned by [userId] existed and was deleted. */
    suspend fun deleteForUser(
        userId: Int,
        installationId: String,
    ): Boolean

    /** System-triggered cleanup (FCM reported the installation id as stale) — not a user action,
     * so no ownership check. */
    suspend fun deleteByInstallationId(installationId: String)
}
