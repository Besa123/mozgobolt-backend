package com.mozgobolt.feature.vehicleTracking.di

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.messaging.MessageRelay
import com.mozgobolt.core.modules.plugin.exemptFromRequestTimeout
import com.mozgobolt.core.routing.API_V1_PREFIX
import com.mozgobolt.core.utility.IdleSharedFlowSweeper
import com.mozgobolt.feature.proximityNotification.domain.ProximityAlertService
import com.mozgobolt.feature.vehicle.domain.VehicleRepository
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import com.mozgobolt.feature.vehicleTracking.data.repository.VehicleLocationRepositoryI
import com.mozgobolt.feature.vehicleTracking.domain.CellIndexer
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationBuffer
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationRepository
import com.mozgobolt.feature.vehicleTracking.domain.VehicleTrackingService
import com.mozgobolt.feature.vehicleTracking.routing.VEHICLE_TRACKING_LIVE_PATH
import com.mozgobolt.feature.vehicleTracking.service.H3CellIndexer
import com.mozgobolt.feature.vehicleTracking.service.InMemoryVehicleLocationBuffer
import com.mozgobolt.feature.vehicleTracking.service.InMemoryVehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.service.RedisRelayVehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.service.VehicleLocationBatchWriter
import com.mozgobolt.feature.vehicleTracking.service.VehicleLocationRetentionPurger
import com.mozgobolt.feature.vehicleTracking.service.VehicleTrackingServiceI
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import io.ktor.server.plugins.di.resolve

fun Application.configureVehicleTrackingDependencyInjection() {
    exemptFromRequestTimeout("$API_V1_PREFIX$VEHICLE_TRACKING_LIVE_PATH")

    dependencies {
        // H3Core.newInstance() loads a native library — do this once, here, not per request; a
        // failure here should fail application startup loudly rather than degrade silently.
        provide<CellIndexer> { H3CellIndexer() }

        // Provided under its own concrete type too (not just VehicleLocationHub) purely so
        // configureVehicleLocationIdleFlowSweeper() below can resolve it and reach flowsByCell —
        // constructing a sweeper is a process lifecycle concern routes/services never see.
        provide<InMemoryVehicleLocationHub> { InMemoryVehicleLocationHub(resolve<CellIndexer>()) }

        // Provided under its own concrete type too (not just VehicleLocationHub) purely so
        // configureVehicleLocationRelay() below can resolve it and call start()/stop() — the
        // relay's cross-instance fanout is a process lifecycle concern routes/services never see.
        provide<RedisRelayVehicleLocationHub> {
            RedisRelayVehicleLocationHub(resolve<InMemoryVehicleLocationHub>(), resolve<MessageRelay>())
        }
        provide<VehicleLocationHub> { resolve<RedisRelayVehicleLocationHub>() }
        provide<VehicleLocationBuffer> { InMemoryVehicleLocationBuffer() }
        provide<VehicleLocationRepository> { VehicleLocationRepositoryI() }
        provide<VehicleTrackingService> {
            VehicleTrackingServiceI(
                vehicleAssignmentService = resolve<VehicleAssignmentService>(),
                vehicleRepository = resolve<VehicleRepository>(),
                cellIndexer = resolve<CellIndexer>(),
                hub = resolve<VehicleLocationHub>(),
                buffer = resolve<VehicleLocationBuffer>(),
                proximityAlertService = resolve<ProximityAlertService>(),
            )
        }

        provide<VehicleLocationBatchWriter> {
            VehicleLocationBatchWriter(
                buffer = resolve<VehicleLocationBuffer>(),
                repository = resolve<VehicleLocationRepository>(),
                tx = resolve<TransactionalRunner>(),
            )
        }

        provide<VehicleLocationRetentionPurger> {
            VehicleLocationRetentionPurger(
                repository = resolve<VehicleLocationRepository>(),
                tx = resolve<TransactionalRunner>(),
            )
        }
    }
}

fun Application.configureVehicleLocationBatchWriter() {
    val writer: VehicleLocationBatchWriter by dependencies
    writer.start()

    monitor.subscribe(ApplicationStopped) {
        writer.stop()
    }
}

fun Application.configureVehicleLocationRetentionPurger() {
    val purger: VehicleLocationRetentionPurger by dependencies
    purger.start()

    monitor.subscribe(ApplicationStopped) {
        purger.stop()
    }
}

fun Application.configureVehicleLocationRelay() {
    val hub: RedisRelayVehicleLocationHub by dependencies
    hub.start()

    monitor.subscribe(ApplicationStopped) {
        hub.stop()
    }
}

fun Application.configureVehicleLocationIdleFlowSweeper() {
    val hub: InMemoryVehicleLocationHub by dependencies
    val sweeper = IdleSharedFlowSweeper(hub.flowsByCell)
    sweeper.start()

    monitor.subscribe(ApplicationStopped) {
        sweeper.stop()
    }
}
