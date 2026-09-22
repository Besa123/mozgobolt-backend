package com.mozgobolt.feature.deviceInstallation.data.mapper

import com.mozgobolt.feature.deviceInstallation.data.database.DeviceInstallationEntity
import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallation

fun DeviceInstallationEntity.toDeviceInstallation() =
    DeviceInstallation(
        id = id.value,
        userId = userId,
        installationId = installationId,
        platform = platform,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
