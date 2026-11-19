/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.proximityNotification.domain.model

data class ProximityNotificationCandidate(
    val buyerUserId: Int,
    val savedLocationId: Int,
)
