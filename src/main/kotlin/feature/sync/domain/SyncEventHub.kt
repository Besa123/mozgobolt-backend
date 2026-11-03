/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.sync.domain

import com.mozgobolt.feature.sync.domain.model.SyncHint
import kotlinx.coroutines.flow.Flow

/** In-process notifications that prompt connected devices to pull durable sync events. */
interface SyncEventHub {
    /** Publishes a hint that the user's change log has advanced. */
    fun publish(
        userId: Int,
        eventId: Long,
        originDeviceId: String? = null,
    )

    /** Subscribes to sync hints for one user. */
    fun subscribe(userId: Int): Flow<SyncHint>
}
