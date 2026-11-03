/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.sync.domain.model

data class SyncChange(
    val userId: Int,
    val entityType: SyncEntityType,
    val entityId: Int,
    val operation: SyncOperation,
    val originDeviceId: String? = null,
)
