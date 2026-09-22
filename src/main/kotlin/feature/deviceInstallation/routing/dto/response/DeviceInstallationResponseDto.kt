package com.mozgobolt.feature.deviceInstallation.routing.dto.response

import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallation
import kotlinx.serialization.Serializable

@Serializable
data class DeviceInstallationResponseDto(
    val id: Int,
    val platform: String,
)

fun DeviceInstallation.toResponseDto() =
    DeviceInstallationResponseDto(
        id = id,
        platform = platform.name,
    )
