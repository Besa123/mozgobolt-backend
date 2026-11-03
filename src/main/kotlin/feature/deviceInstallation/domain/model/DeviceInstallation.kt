/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.deviceInstallation.domain.model

import java.time.Instant

data class DeviceInstallation(
    val id: Int,
    val userId: Int,
    val installationId: String,
    val platform: DevicePlatform,
    val createdAt: Instant,
    val updatedAt: Instant,
    val registrationVersion: Long = 1L,
)
