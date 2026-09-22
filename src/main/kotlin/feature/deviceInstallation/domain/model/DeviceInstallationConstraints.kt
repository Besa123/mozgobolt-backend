package com.mozgobolt.feature.deviceInstallation.domain.model

/**
 * Single source of truth shared between [DeviceInstallation] validation and
 * [com.mozgobolt.feature.deviceInstallation.data.database.DeviceInstallationsTable]'s column
 * widths — see [com.mozgobolt.feature.company.domain.model.CompanyConstraints] for the same
 * pattern.
 */
object DeviceInstallationConstraints {
    /** Generous headroom over a Firebase Installation ID's real-world length — Firebase does not
     * publish an exact maximum. */
    const val INSTALLATION_ID_MAX_LENGTH = 4096

    /** Longest [DevicePlatform] name is "ANDROID" (7 chars), rounded up with headroom the same
     * way [com.mozgobolt.feature.company.domain.model.CompanyConstraints.ROLE_COLUMN_LENGTH] is. */
    const val PLATFORM_COLUMN_LENGTH = 10
}
