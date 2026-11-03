/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt

import com.mozgobolt.core.database.DatabaseFactory.runFlywayMigration
import com.mozgobolt.core.di.configureDependencyInjection
import com.mozgobolt.core.di.configureIdempotencyRetentionPurger
import com.mozgobolt.core.di.configureMessageRelay
import com.mozgobolt.core.modules.configureModules
import com.mozgobolt.feature.sync.di.configureSyncEventListener
import com.mozgobolt.feature.user.di.configureUserEmailOutboxWorker
import com.mozgobolt.feature.user.di.configureUserSecurityDataRetentionPurger
import com.mozgobolt.feature.vehicleAssignment.di.configureVehicleAssignmentStaleSessionReaper
import com.mozgobolt.feature.vehiclePing.di.configureVehiclePingIdleFlowSweeper
import com.mozgobolt.feature.vehiclePing.di.configureVehiclePingRelay
import com.mozgobolt.feature.vehiclePing.di.configureVehiclePingRetentionPurger
import com.mozgobolt.feature.vehicleTracking.di.configureVehicleLocationBatchWriter
import com.mozgobolt.feature.vehicleTracking.di.configureVehicleLocationIdleFlowSweeper
import com.mozgobolt.feature.vehicleTracking.di.configureVehicleLocationRelay
import com.mozgobolt.feature.vehicleTracking.di.configureVehicleLocationRetentionPurger
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import javax.sql.DataSource

/** Installs application dependencies, plugins, routes, migrations, and background workers. */
fun Application.rootModule() {
    configureDependencyInjection()
    configureModules()
    configureRouting()

    val database: DataSource by dependencies
    runFlywayMigration(database)

    configureIdempotencyRetentionPurger()
    configureUserEmailOutboxWorker()
    configureUserSecurityDataRetentionPurger()
    configureSyncEventListener()
    configureVehicleAssignmentStaleSessionReaper()
    configureVehicleLocationBatchWriter()
    configureVehicleLocationRetentionPurger()
    configureVehiclePingRetentionPurger()

    // The relay must be connected before either hub below tries to subscribe through it.
    configureMessageRelay()
    configureVehicleLocationRelay()
    configureVehiclePingRelay()

    configureVehicleLocationIdleFlowSweeper()
    configureVehiclePingIdleFlowSweeper()
}
