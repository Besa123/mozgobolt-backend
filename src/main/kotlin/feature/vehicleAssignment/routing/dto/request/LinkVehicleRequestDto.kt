/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleAssignment.routing.dto.request

import com.mozgobolt.core.domain.validation.ValidatedRequest
import kotlinx.serialization.Serializable

@Serializable
data class LinkVehicleRequestDto(
    val shareLocationWithCustomers: Boolean,
) : ValidatedRequest {
    override fun validate() = emptyList<String>()
}
