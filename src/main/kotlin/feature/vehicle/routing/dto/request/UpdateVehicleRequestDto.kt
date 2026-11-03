/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicle.routing.dto.request

import com.mozgobolt.core.domain.validation.ValidatedRequest
import kotlinx.serialization.Serializable

@Serializable
data class UpdateVehicleRequestDto(
    val licensePlate: String,
    val pictureUrl: String? = null,
    val clearPictureUrl: Boolean = false,
) : ValidatedRequest {
    override fun validate() =
        buildList {
            addAll(validateLicensePlate(licensePlate))
            if (pictureUrl != null && clearPictureUrl) {
                add("pictureUrl cannot be set when clearPictureUrl is true")
            }
        }
}
