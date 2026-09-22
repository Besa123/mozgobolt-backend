package com.mozgobolt.feature.vehiclePing.di

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.messaging.MessageRelay
import com.mozgobolt.core.modules.plugin.exemptFromRequestTimeout
import com.mozgobolt.core.routing.API_V1_PREFIX
import com.mozgobolt.core.utility.IdleSharedFlowSweeper
import com.mozgobolt.feature.deviceInstallation.domain.DeviceInstallationService
import com.mozgobolt.feature.vehicle.domain.VehicleRepository
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import com.mozgobolt.feature.vehiclePing.data.repository.VehiclePingRepositoryI
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingHub
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingRepository
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingService
import com.mozgobolt.feature.vehiclePing.routing.VEHICLE_PING_LIVE_PATH
import com.mozgobolt.feature.vehiclePing.service.InMemoryVehiclePingHub
import com.mozgobolt.feature.vehiclePing.service.PING_LOCATION_RESOLUTION
import com.mozgobolt.feature.vehiclePing.service.RedisRelayVehiclePingHub
import com.mozgobolt.feature.vehiclePing.service.VehiclePingServiceI
import com.mozgobolt.feature.vehicleTracking.service.H3CellIndexer
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import io.ktor.server.plugins.di.resolve

fun Application.configureVehiclePingDependencyInjection() {
    exemptFromRequestTimeout("$API_V1_PREFIX$VEHICLE_PING_LIVE_PATH")

    dependencies {
        // Provided under its own concrete type too, same reasoning as
        // feature.vehicleTracking.di's InMemoryVehicleLocationHub: only
        // configureVehiclePingIdleFlowSweeper() below needs it, to reach flowsByVendorUserId.
        provide<InMemoryVehiclePingHub> { InMemoryVehiclePingHub() }

        // Provided under its own concrete type too, same reasoning as
        // feature.vehicleTracking.di's RedisRelayVehicleLocationHub: only
        // configureVehiclePingRelay() below needs it, to call start()/stop().
        provide<RedisRelayVehiclePingHub> {
            RedisRelayVehiclePingHub(resolve<InMemoryVehiclePingHub>(), resolve<MessageRelay>())
        }
        provide<VehiclePingHub> { resolve<RedisRelayVehiclePingHub>() }
        provide<VehiclePingRepository> { VehiclePingRepositoryI() }
        provide<VehiclePingService> {
            VehiclePingServiceI(
                pingRepository = resolve<VehiclePingRepository>(),
                vehicleRepository = resolve<VehicleRepository>(),
                vehicleAssignmentService = resolve<VehicleAssignmentService>(),
                pingHub = resolve<VehiclePingHub>(),
                deviceInstallationService = resolve<DeviceInstallationService>(),
                // A dedicated instance, deliberately not the globally-provided CellIndexer (which
                // is fixed at H3CellIndexer.DEFAULT_RESOLUTION for the live-map's search-radius
                // concern) — ping-location privacy coarsening needs its own, finer resolution.
                cellIndexer = H3CellIndexer(resolution = PING_LOCATION_RESOLUTION),
                tx = resolve<TransactionalRunner>(),
            )
        }
    }
}

fun Application.configureVehiclePingRelay() {
    val hub: RedisRelayVehiclePingHub by dependencies
    hub.start()

    monitor.subscribe(ApplicationStopped) {
        hub.stop()
    }
}

fun Application.configureVehiclePingIdleFlowSweeper() {
    val hub: InMemoryVehiclePingHub by dependencies
    val sweeper = IdleSharedFlowSweeper(hub.flowsByVendorUserId)
    sweeper.start()

    monitor.subscribe(ApplicationStopped) {
        sweeper.stop()
    }
}
