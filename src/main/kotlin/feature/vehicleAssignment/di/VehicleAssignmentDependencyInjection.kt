package com.mozgobolt.feature.vehicleAssignment.di

import com.mozgobolt.feature.vehicleAssignment.data.repository.VehicleAssignmentRepositoryI
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentRepository
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import com.mozgobolt.feature.vehicleAssignment.service.VehicleAssignmentServiceI
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide

fun Application.configureVehicleAssignmentDependencyInjection() {
    dependencies {
        provide<VehicleAssignmentRepository> { VehicleAssignmentRepositoryI() }
        provide<VehicleAssignmentService>(::VehicleAssignmentServiceI)
    }
}
