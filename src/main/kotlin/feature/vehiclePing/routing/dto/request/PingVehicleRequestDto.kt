package com.mozgobolt.feature.vehiclePing.routing.dto.request

import com.mozgobolt.core.domain.validation.ValidatedRequest
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoBounds
import kotlinx.serialization.Serializable

/**
 * The buyer's real, exact position when they tapped ping — validated here, then immediately
 * coarsened by [com.mozgobolt.feature.vehiclePing.service.VehiclePingServiceI] before it touches
 * anything else. This DTO is the only place in the backend that ever holds the exact value.
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
