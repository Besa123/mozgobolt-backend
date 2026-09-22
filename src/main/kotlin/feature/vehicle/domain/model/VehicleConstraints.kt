package com.mozgobolt.feature.vehicle.domain.model

/**
 * Real-world license plates rarely exceed 10-12 characters; this leaves generous headroom rather
 * than guessing the exact ceiling, matching this project's existing constant-sizing convention
 * (e.g. [com.mozgobolt.feature.company.domain.model.CompanyConstraints.ROLE_COLUMN_LENGTH]).
 * Shared between [com.mozgobolt.feature.vehicle.routing.dto.request.CreateVehicleRequestDto]'s
 * validation and [com.mozgobolt.feature.vehicle.data.database.VehiclesTable]'s column width.
 */
object VehicleConstraints {
    const val LICENSE_PLATE_MAX_LENGTH = 20
}
