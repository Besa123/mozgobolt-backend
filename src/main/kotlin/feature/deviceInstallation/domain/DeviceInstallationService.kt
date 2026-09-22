package com.mozgobolt.feature.deviceInstallation.domain

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallation
import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallationError
import com.mozgobolt.feature.deviceInstallation.domain.model.DevicePlatform

interface DeviceInstallationService {
    suspend fun registerInstallation(
        userId: Int,
        installationId: String,
        platform: DevicePlatform,
    ): DeviceInstallation

    suspend fun unregisterInstallation(
        userId: Int,
        installationId: String,
    ): AppResult<Unit, DeviceInstallationError>

    /**
     * Delivers [data] to every device installation registered to [userId], concurrently and
     * independently of one another — one installation's failure, or its self-heal delete on an
     * invalid push target, never affects another's send. Shared by every feature that pushes to a
     * user (pings, proximity alerts, ...) so this fetch/send/self-heal shape lives once, not once
     * per feature — and lives here, on the service that owns this feature's data, rather than as
     * an extension bolted onto its repository from outside.
     */
    suspend fun sendPush(
        userId: Int,
        data: Map<String, String>,
    )
}
