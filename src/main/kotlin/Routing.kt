package com.mozgobolt

import com.mozgobolt.core.data.idempotency.IdempotencyStore
import com.mozgobolt.core.routing.apiV1
import com.mozgobolt.feature.company.domain.CompanyService
import com.mozgobolt.feature.company.routing.companyRoutes
import com.mozgobolt.feature.companyFavorite.domain.CompanyFavoriteService
import com.mozgobolt.feature.companyFavorite.routing.companyFavoriteRoutes
import com.mozgobolt.feature.deviceInstallation.domain.DeviceInstallationService
import com.mozgobolt.feature.deviceInstallation.routing.deviceInstallationRoutes
import com.mozgobolt.feature.health.routing.infrastructureRoutes
import com.mozgobolt.feature.savedLocation.domain.SavedLocationService
import com.mozgobolt.feature.savedLocation.routing.savedLocationRoutes
import com.mozgobolt.feature.sync.domain.SyncEventHub
import com.mozgobolt.feature.sync.domain.SyncService
import com.mozgobolt.feature.sync.routing.syncRoutes
import com.mozgobolt.feature.user.domain.UserService
import com.mozgobolt.feature.user.routing.authRoutes
import com.mozgobolt.feature.vehicle.domain.VehicleService
import com.mozgobolt.feature.vehicle.routing.vehicleRoutes
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import com.mozgobolt.feature.vehicleAssignment.routing.vehicleAssignmentRoutes
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingHub
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingService
import com.mozgobolt.feature.vehiclePing.routing.vehiclePingRoutes
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.VehicleTrackingService
import com.mozgobolt.feature.vehicleTracking.routing.vehicleTrackingRoutes
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.routing
import javax.sql.DataSource

fun Application.configureRouting() {
    val userService: UserService by dependencies
    val syncService: SyncService by dependencies
    val syncEventHub: SyncEventHub by dependencies
    val companyService: CompanyService by dependencies
    val companyFavoriteService: CompanyFavoriteService by dependencies
    val savedLocationService: SavedLocationService by dependencies
    val vehicleService: VehicleService by dependencies
    val vehicleAssignmentService: VehicleAssignmentService by dependencies
    val vehicleLocationHub: VehicleLocationHub by dependencies
    val vehicleTrackingService: VehicleTrackingService by dependencies
    val vehiclePingService: VehiclePingService by dependencies
    val vehiclePingHub: VehiclePingHub by dependencies
    val deviceInstallationService: DeviceInstallationService by dependencies
    val idempotencyStore: IdempotencyStore by dependencies
    val dataSource: DataSource by dependencies

    routing {
        infrastructureRoutes(dataSource)

        apiV1 {
            authRoutes(userService, idempotencyStore)
            syncRoutes(syncService, syncEventHub)
            companyRoutes(companyService, idempotencyStore)
            companyFavoriteRoutes(companyFavoriteService)
            savedLocationRoutes(savedLocationService, idempotencyStore)
            vehicleRoutes(vehicleService, vehicleAssignmentService, userService, idempotencyStore)
            vehicleAssignmentRoutes(vehicleAssignmentService)
            vehicleTrackingRoutes(vehicleTrackingService, vehicleLocationHub)
            vehiclePingRoutes(vehiclePingService, vehiclePingHub)
            deviceInstallationRoutes(deviceInstallationService)
        }
    }
}
