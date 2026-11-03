/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.deviceInstallation.data.database

import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallationConstraints
import com.mozgobolt.feature.deviceInstallation.domain.model.DevicePlatform
import com.mozgobolt.feature.user.data.database.UsersTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.javatime.timestamp

data object DeviceInstallationsTable : IntIdTable("device_installations") {
    val userId =
        integer("user_id")
            .references(
                UsersTable.id,
                onDelete = ReferenceOption.CASCADE,
                onUpdate = ReferenceOption.RESTRICT,
            ).index()
    val installationId = varchar("installation_id", DeviceInstallationConstraints.INSTALLATION_ID_MAX_LENGTH)
    val installationIdHash =
        varchar("installation_id_hash", DeviceInstallationConstraints.INSTALLATION_ID_HASH_LENGTH)
    val platform = enumerationByName<DevicePlatform>("platform", DeviceInstallationConstraints.PLATFORM_COLUMN_LENGTH)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val registrationVersion = long("registration_version").default(1L)

    init {
        check("device_installations_registration_version_positive_check") {
            registrationVersion greaterEq 1L
        }
        uniqueIndex(installationIdHash)
    }
}
