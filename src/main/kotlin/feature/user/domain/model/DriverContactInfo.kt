/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.domain.model

/** A driver's public identity and optional contact methods for a vehicle response. */
data class DriverContactInfo(
    val displayName: String,
    val phoneNumber: String?,
    val whatsappNumber: String?,
    val viberNumber: String?,
    val messengerUsername: String?,
)
