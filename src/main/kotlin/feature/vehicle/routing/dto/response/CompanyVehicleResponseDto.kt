/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicle.routing.dto.response

import com.mozgobolt.core.domain.contact.messengerLink
import com.mozgobolt.core.domain.contact.viberLink
import com.mozgobolt.core.domain.contact.whatsAppLink
import com.mozgobolt.feature.user.domain.model.DriverContactInfo
import com.mozgobolt.feature.vehicle.domain.model.Vehicle
import kotlinx.serialization.Serializable

@Serializable
enum class VehicleAvailability {
    AVAILABLE,
    IN_USE,
}

@Serializable
data class CompanyVehicleResponseDto(
    val id: Int,
    val companyId: Int,
    val label: String,
    val licensePlate: String,
    val pictureUrl: String?,
    val availability: VehicleAvailability,
    val currentDriverName: String?,
    val currentDriverPhoneNumber: String?,
    val currentDriverWhatsAppLink: String?,
    val currentDriverViberLink: String?,
    val currentDriverMessengerLink: String?,
)

fun Vehicle.toCompanyVehicleResponseDto(currentDriverInfo: DriverContactInfo?) =
    CompanyVehicleResponseDto(
        id = id,
        companyId = companyId,
        label = label,
        licensePlate = licensePlate,
        pictureUrl = pictureUrl,
        availability = if (currentDriverInfo == null) VehicleAvailability.AVAILABLE else VehicleAvailability.IN_USE,
        currentDriverName = currentDriverInfo?.displayName,
        currentDriverPhoneNumber = currentDriverInfo?.phoneNumber,
        currentDriverWhatsAppLink = currentDriverInfo?.whatsappNumber?.let(::whatsAppLink),
        currentDriverViberLink = currentDriverInfo?.viberNumber?.let(::viberLink),
        currentDriverMessengerLink = currentDriverInfo?.messengerUsername?.let(::messengerLink),
    )
