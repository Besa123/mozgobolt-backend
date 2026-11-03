/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.deviceInstallation.data.mapper

import com.mozgobolt.feature.deviceInstallation.data.database.DeviceInstallationsTable
import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallation
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toDeviceInstallation() =
    DeviceInstallation(
        id = this[DeviceInstallationsTable.id].value,
        userId = this[DeviceInstallationsTable.userId],
        installationId = this[DeviceInstallationsTable.installationId],
        platform = this[DeviceInstallationsTable.platform],
        createdAt = this[DeviceInstallationsTable.createdAt],
        updatedAt = this[DeviceInstallationsTable.updatedAt],
        registrationVersion = this[DeviceInstallationsTable.registrationVersion],
    )
