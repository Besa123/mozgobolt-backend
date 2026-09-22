package com.mozgobolt.feature.deviceInstallation.routing.dto.request

import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallationConstraints
import kotlin.test.Test
import kotlin.test.assertTrue

class RegisterDeviceInstallationRequestDtoTest {
    @Test
    fun `a well-formed installation id and platform has no validation errors`() {
        assertTrue(
            RegisterDeviceInstallationRequestDto(installationId = "fcm-fid", platform = "ANDROID").validate().isEmpty(),
        )
    }

    @Test
    fun `IOS is also a valid platform`() {
        assertTrue(
            RegisterDeviceInstallationRequestDto(installationId = "fcm-fid", platform = "IOS").validate().isEmpty(),
        )
    }

    @Test
    fun `a blank installation id is rejected`() {
        assertTrue(
            RegisterDeviceInstallationRequestDto(installationId = "", platform = "ANDROID").validate().isNotEmpty(),
        )
    }

    @Test
    fun `an installation id over the max length is rejected`() {
        val tooLong = "a".repeat(DeviceInstallationConstraints.INSTALLATION_ID_MAX_LENGTH + 1)
        assertTrue(
            RegisterDeviceInstallationRequestDto(
                installationId = tooLong,
                platform = "ANDROID",
            ).validate().isNotEmpty(),
        )
    }

    @Test
    fun `an installation id at exactly the max length is accepted`() {
        val exactLength = "a".repeat(DeviceInstallationConstraints.INSTALLATION_ID_MAX_LENGTH)
        assertTrue(
            RegisterDeviceInstallationRequestDto(
                installationId = exactLength,
                platform = "ANDROID",
            ).validate().isEmpty(),
        )
    }

    @Test
    fun `an unknown platform value is rejected`() {
        assertTrue(
            RegisterDeviceInstallationRequestDto(installationId = "fcm-fid", platform = "WINDOWS_PHONE")
                .validate()
                .isNotEmpty(),
        )
    }

    @Test
    fun `a lowercase platform value is rejected — enum matching is case-sensitive by design`() {
        assertTrue(
            RegisterDeviceInstallationRequestDto(
                installationId = "fcm-fid",
                platform = "android",
            ).validate().isNotEmpty(),
        )
    }

    @Test
    fun `a blank platform value is rejected`() {
        assertTrue(
            RegisterDeviceInstallationRequestDto(installationId = "fcm-fid", platform = "").validate().isNotEmpty(),
        )
    }
}
