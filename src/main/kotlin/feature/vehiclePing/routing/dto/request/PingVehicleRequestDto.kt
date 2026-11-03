/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehiclePing.routing.dto.request

import com.mozgobolt.core.domain.validation.ValidatedRequest
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoBounds
import kotlinx.serialization.Serializable

/**
 * The buyer's exact position when they tapped ping. It is validated here and immediately
 * coarsened by [com.mozgobolt.feature.vehiclePing.service.VehiclePingServiceI] before repository,
 * log, or delivery use; only the coarsened point is sent to the driver.
 */
@Serializable
data class PingVehicleRequestDto(
    val latitude: Double,
    val longitude: Double,
) : ValidatedRequest {
    override fun validate() =
        buildList {
            if (latitude !in GeoBounds.MIN_LATITUDE..GeoBounds.MAX_LATITUDE) {
                add("Latitude must be between ${GeoBounds.MIN_LATITUDE} and ${GeoBounds.MAX_LATITUDE}")
            }
            if (longitude !in GeoBounds.MIN_LONGITUDE..GeoBounds.MAX_LONGITUDE) {
                add("Longitude must be between ${GeoBounds.MIN_LONGITUDE} and ${GeoBounds.MAX_LONGITUDE}")
            }
        }
}
