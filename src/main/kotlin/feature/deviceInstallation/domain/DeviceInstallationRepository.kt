/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.deviceInstallation.domain

import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallation
import com.mozgobolt.feature.deviceInstallation.domain.model.DevicePlatform
import java.time.Instant

/** Persistence operations for user-owned device installations. */
interface DeviceInstallationRepository {
    /**
     * Registers an installation or refreshes its platform and [DeviceInstallation.updatedAt].
     * The installation ID has one current owner; registering it for another user transfers
     * ownership rather than allowing pushes to reach multiple accounts on the same app install.
     */
    suspend fun register(
        userId: Int,
        installationId: String,
        platform: DevicePlatform,
        now: Instant,
    ): DeviceInstallation

    /** Lists the device installations currently owned by [userId]. */
    suspend fun findAllForUser(userId: Int): List<DeviceInstallation>

    /** `true` if a matching row owned by [userId] existed and was deleted. */
    suspend fun deleteForUser(
        userId: Int,
        installationId: String,
    ): Boolean

    /**
     * Removes [installation] only if it is still the exact registration snapshot delivered to.
     * A stale push failure must not delete an installation that was transferred or re-registered
     * while the send was in flight.
     */
    suspend fun deleteIfCurrent(installation: DeviceInstallation): Boolean
}
