/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleAssignment.di

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.feature.sync.domain.SyncService
import com.mozgobolt.feature.vehicleAssignment.data.repository.VehicleAssignmentRepositoryI
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentRepository
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentSessionTerminator
import com.mozgobolt.feature.vehicleAssignment.service.VehicleAssignmentServiceI
import com.mozgobolt.feature.vehicleAssignment.service.VehicleAssignmentStaleSessionReaper
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import io.ktor.server.plugins.di.resolve

fun Application.configureVehicleAssignmentDependencyInjection() {
    dependencies {
        provide<VehicleAssignmentRepository> { VehicleAssignmentRepositoryI() }
        provide<VehicleAssignmentServiceI>(::VehicleAssignmentServiceI)
        provide<VehicleAssignmentService> { resolve<VehicleAssignmentServiceI>() }
        provide<VehicleAssignmentSessionTerminator> { resolve<VehicleAssignmentServiceI>() }
        provide<VehicleAssignmentStaleSessionReaper> {
            VehicleAssignmentStaleSessionReaper(
                vehicleAssignmentRepository = resolve<VehicleAssignmentRepository>(),
                syncService = resolve<SyncService>(),
                hub = resolve<VehicleLocationHub>(),
                tx = resolve<TransactionalRunner>(),
                backgroundScope = resolve<ApplicationBackgroundScope>(),
            )
        }
    }
}

fun Application.configureVehicleAssignmentStaleSessionReaper() {
    val reaper: VehicleAssignmentStaleSessionReaper by dependencies
    reaper.start()
}
