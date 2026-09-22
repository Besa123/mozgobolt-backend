package com.mozgobolt.feature.vehicle.routing.dto.request

import com.mozgobolt.core.domain.validation.DISPLAY_NAME_MAX_LENGTH
import com.mozgobolt.core.domain.validation.ValidatedRequest
import com.mozgobolt.core.domain.validation.validateDisplayName
import com.mozgobolt.feature.vehicle.domain.model.VehicleConstraints
import kotlinx.serialization.Serializable

@Serializable
data class CreateVehicleRequestDto(
    val label: String,
    val licensePlate: String,
    val pictureUrl: String? = null,
) : ValidatedRequest {
    override fun validate() =
        buildList {
            addAll(validateDisplayName(label, DISPLAY_NAME_MAX_LENGTH, fieldLabel = "Vehicle label"))
            addAll(validateLicensePlate(licensePlate))
        }
}

internal fun validateLicensePlate(licensePlate: String): List<String> =
    buildList {
        if (licensePlate.isBlank()) add("License plate is required")
        if (licensePlate.length > VehicleConstraints.LICENSE_PLATE_MAX_LENGTH) {
            add("License plate must be ${VehicleConstraints.LICENSE_PLATE_MAX_LENGTH} characters or less")
        }
    }
