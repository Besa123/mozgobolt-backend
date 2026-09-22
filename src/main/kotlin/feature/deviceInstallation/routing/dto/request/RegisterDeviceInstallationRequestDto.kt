package com.mozgobolt.feature.deviceInstallation.routing.dto.request

import com.mozgobolt.core.domain.validation.ValidatedRequest
import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallationConstraints
import com.mozgobolt.feature.deviceInstallation.domain.model.DevicePlatform
import kotlinx.serialization.Serializable

@Serializable
data class RegisterDeviceInstallationRequestDto(
    val installationId: String,
    val platform: String,
) : ValidatedRequest {
    override fun validate() =
        buildList {
            val maxLength = DeviceInstallationConstraints.INSTALLATION_ID_MAX_LENGTH
            if (installationId.isBlank()) add("Installation id is required")
            if (installationId.length > maxLength) {
                add("Installation id must be $maxLength characters or less")
            }
            if (DevicePlatform.entries.none { it.name == platform }) {
                add("Platform must be one of ${DevicePlatform.entries.joinToString { it.name }}")
            }
        }
}
