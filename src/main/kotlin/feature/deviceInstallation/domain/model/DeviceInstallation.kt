package com.mozgobolt.feature.deviceInstallation.domain.model

import java.time.Instant

data class DeviceInstallation(
    val id: Int,
    val userId: Int,
    val installationId: String,
    val platform: DevicePlatform,
    val createdAt: Instant,
    val updatedAt: Instant,
)
