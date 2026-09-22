package com.mozgobolt.feature.vehicle.routing.dto.request

import com.mozgobolt.core.domain.validation.ValidatedRequest
import kotlinx.serialization.Serializable

@Serializable
data class UpdateVehicleRequestDto(
    val licensePlate: String,
    val pictureUrl: String? = null,
) : ValidatedRequest {
    override fun validate() = validateLicensePlate(licensePlate)
}
