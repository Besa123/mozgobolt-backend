/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicle.routing

import com.mozgobolt.core.domain.contact.messengerLink
import com.mozgobolt.core.domain.contact.viberLink
import com.mozgobolt.core.domain.contact.whatsAppLink
import com.mozgobolt.feature.user.domain.UserService
import com.mozgobolt.feature.user.domain.model.DriverContactInfo
import com.mozgobolt.feature.vehicle.domain.model.Vehicle
import com.mozgobolt.feature.vehicle.routing.dto.response.CompanyVehicleResponseDto
import com.mozgobolt.feature.vehicle.routing.dto.response.VehicleResponseDto
import com.mozgobolt.feature.vehicle.routing.dto.response.toCompanyVehicleResponseDto
import com.mozgobolt.feature.vehicle.routing.dto.response.toResponseDto
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService

/** Adds visible contact methods without exposing company-only driver details. */
internal suspend fun Vehicle.toResponseDtoWithDriverInfo(
    vehicleAssignmentService: VehicleAssignmentService,
    userService: UserService,
): VehicleResponseDto {
    val contactInfo = findCurrentDriverInfo(vehicleAssignmentService, userService)
    return toResponseDto(
        currentDriverPhoneNumber = contactInfo?.phoneNumber,
        currentDriverWhatsAppLink = contactInfo?.run { whatsappNumber?.let(::whatsAppLink) },
        currentDriverViberLink = contactInfo?.run { viberNumber?.let(::viberLink) },
        currentDriverMessengerLink = contactInfo?.run { messengerUsername?.let(::messengerLink) },
    )
}

/** Adds the company-only driver identity and availability for a company-scoped vehicle response. */
internal suspend fun Vehicle.toCompanyResponseDtoWithDriverInfo(
    vehicleAssignmentService: VehicleAssignmentService,
    userService: UserService,
): CompanyVehicleResponseDto {
    val contactInfo = findCurrentDriverInfo(vehicleAssignmentService, userService)
    check(contactInfo?.run { displayName.isNotBlank() } != false) {
        "An active driver must have a non-blank display name"
    }
    return toCompanyVehicleResponseDto(contactInfo)
}

private suspend fun Vehicle.findCurrentDriverInfo(
    vehicleAssignmentService: VehicleAssignmentService,
    userService: UserService,
): DriverContactInfo? {
    val activeAssignment = vehicleAssignmentService.findActiveForVehicle(id) ?: return null
    return checkNotNull(userService.findContactInfo(activeAssignment.vendorUserId)) {
        "An active assignment must reference an existing user"
    }
}
