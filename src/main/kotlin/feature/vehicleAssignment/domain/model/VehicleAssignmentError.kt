package com.mozgobolt.feature.vehicleAssignment.domain.model

enum class VehicleAssignmentError {
    VEHICLE_NOT_FOUND,
    FORBIDDEN,
    VEHICLE_ALREADY_ASSIGNED,
    VEHICLE_ARCHIVED,
    NOT_LINKED,
    NOT_ADMIN,
    NO_ACTIVE_ASSIGNMENT,
}
