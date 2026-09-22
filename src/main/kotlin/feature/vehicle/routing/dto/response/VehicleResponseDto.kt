package com.mozgobolt.feature.vehicle.routing.dto.response

import com.mozgobolt.feature.vehicle.domain.model.Vehicle
import kotlinx.serialization.Serializable

@Serializable
data class VehicleResponseDto(
    val id: Int,
    val companyId: Int,
    val label: String,
    val licensePlate: String,
    val pictureUrl: String?,
    // All derived from whichever VehicleAssignment is currently active for this vehicle — never
    // stored on the vehicle itself, so there's exactly one source of truth for "who's driving
    // this right now." Each is absent when there's no active session, or the driver didn't
    // provide that contact method. The three link fields are already-built deep links (see
    // core.domain.contact.ContactLinks) ready for a client to open directly, not raw
    // numbers/usernames.
    val currentDriverPhoneNumber: String? = null,
    val currentDriverWhatsAppLink: String? = null,
    val currentDriverViberLink: String? = null,
    val currentDriverMessengerLink: String? = null,
)

fun Vehicle.toResponseDto(
    currentDriverPhoneNumber: String? = null,
    currentDriverWhatsAppLink: String? = null,
    currentDriverViberLink: String? = null,
    currentDriverMessengerLink: String? = null,
) = VehicleResponseDto(
    id = id,
    companyId = companyId,
    label = label,
    licensePlate = licensePlate,
    pictureUrl = pictureUrl,
    currentDriverPhoneNumber = currentDriverPhoneNumber,
    currentDriverWhatsAppLink = currentDriverWhatsAppLink,
    currentDriverViberLink = currentDriverViberLink,
    currentDriverMessengerLink = currentDriverMessengerLink,
)
