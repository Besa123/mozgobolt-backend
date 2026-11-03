/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.sync.domain.model

data class SyncHint(
    val eventId: Long,
    val originDeviceId: String?,
) {
    /** Reports whether this hint originated from [deviceId]. */
    @Suppress("DataClassContainsFunctions")
    fun isOwnAction(deviceId: String?): Boolean = deviceId != null && originDeviceId == deviceId
}
