package com.mozgobolt.feature.vehicle.di

import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import com.mozgobolt.feature.vehicle.domain.VehicleRepository
import com.mozgobolt.feature.vehicle.domain.VehicleService
import com.mozgobolt.feature.vehicle.service.VehicleServiceI
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide

fun Application.configureVehicleDependencyInjection() {
    dependencies {
        provide<VehicleRepository> { VehicleRepositoryI() }
        provide<VehicleService>(::VehicleServiceI)
    }
}
