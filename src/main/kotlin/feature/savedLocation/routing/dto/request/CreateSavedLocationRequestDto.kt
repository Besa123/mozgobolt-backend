package com.mozgobolt.feature.savedLocation.routing.dto.request

import com.mozgobolt.core.domain.validation.DISPLAY_NAME_MAX_LENGTH
import com.mozgobolt.core.domain.validation.ValidatedRequest
import com.mozgobolt.core.domain.validation.validateDisplayName
import com.mozgobolt.feature.savedLocation.domain.model.SavedLocationConstraints
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoBounds
import kotlinx.serialization.Serializable

@Serializable
data class CreateSavedLocationRequestDto(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val radiusKm: Double,
) : ValidatedRequest {
    override fun validate() =
        buildList {
            addAll(validateDisplayName(label, DISPLAY_NAME_MAX_LENGTH, fieldLabel = "Label"))
            if (latitude !in GeoBounds.MIN_LATITUDE..GeoBounds.MAX_LATITUDE) {
                add("Latitude must be between ${GeoBounds.MIN_LATITUDE} and ${GeoBounds.MAX_LATITUDE}")
            }
            if (longitude !in GeoBounds.MIN_LONGITUDE..GeoBounds.MAX_LONGITUDE) {
                add("Longitude must be between ${GeoBounds.MIN_LONGITUDE} and ${GeoBounds.MAX_LONGITUDE}")
            }
            if (radiusKm < SavedLocationConstraints.MIN_RADIUS_KM ||
                radiusKm > SavedLocationConstraints.MAX_RADIUS_KM
            ) {
                add(
                    "Radius must be between ${SavedLocationConstraints.MIN_RADIUS_KM} and " +
                        "${SavedLocationConstraints.MAX_RADIUS_KM} km",
                )
            }
        }
}
