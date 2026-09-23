package com.mozgobolt.feature.vehiclePing

import com.mozgobolt.core.data.push.FakePushNotificationSender
import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.company.data.repository.CompanyMembershipRepositoryI
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.deviceInstallation.data.repository.DeviceInstallationRepositoryI
import com.mozgobolt.feature.deviceInstallation.service.DeviceInstallationServiceI
import com.mozgobolt.feature.sync.routing.FakeSyncService
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import com.mozgobolt.feature.vehicleAssignment.data.repository.VehicleAssignmentRepositoryI
import com.mozgobolt.feature.vehicleAssignment.service.VehicleAssignmentServiceI
import com.mozgobolt.feature.vehiclePing.data.repository.VehiclePingRepositoryI
import com.mozgobolt.feature.vehiclePing.service.InMemoryVehiclePingHub
import com.mozgobolt.feature.vehiclePing.service.PING_LOCATION_RESOLUTION
import com.mozgobolt.feature.vehiclePing.service.VehiclePingServiceI
import com.mozgobolt.feature.vehicleTracking.NoOpVehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.service.H3CellIndexer

data class VehiclePingTestHarness(
    val service: VehiclePingServiceI,
    val pingRepository: VehiclePingRepositoryI,
    val hub: InMemoryVehiclePingHub,
    val pushSender: FakePushNotificationSender,
    val tx: TransactionalRunner,
) {
    /** Puts both of [withRealDatabase]'s seeded users into a shared company (user 2 as the
     * vendor, actively driving the returned vehicle) and returns that vehicle's id — the shared
     * starting point every test in this harness needs so a buyer (user 1) can ping it. */
    suspend fun seedActiveVehicleDrivenByUserTwo(): Int =
        tx.transactional {
            val companyRepository = CompanyRepositoryI()
            val membershipRepository = CompanyMembershipRepositoryI()
            val vehicleRepository = VehicleRepositoryI()
            val assignmentRepository = VehicleAssignmentRepositoryI()
            val assignmentService =
                VehicleAssignmentServiceI(
                    vehicleAssignmentRepository = assignmentRepository,
                    vehicleRepository = vehicleRepository,
                    membershipRepository = membershipRepository,
                    syncService = FakeSyncService(),
                    hub = NoOpVehicleLocationHub(),
                    tx = tx,
                )

            val company =
                companyRepository.create(
                    name = "Ping Co",
                    inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                )
            membershipRepository.addIfAbsent(company.id, 2, CompanyRole.MEMBER)
            val vehicle =
                vehicleRepository.create(companyId = company.id, label = "Ping Truck", licensePlate = "PING-1")!!
            assignmentService.link(vendorUserId = 2, vehicleId = vehicle.id)

            vehicle.id
        }
}

fun withRealVehiclePingDatabase(block: suspend (VehiclePingTestHarness) -> Unit) {
    withRealDatabase { _, tx ->
        val pingRepository = VehiclePingRepositoryI()
        val hub = InMemoryVehiclePingHub()
        val deviceInstallationRepository = DeviceInstallationRepositoryI()
        val pushSender = FakePushNotificationSender()
        val deviceInstallationService = DeviceInstallationServiceI(deviceInstallationRepository, tx, pushSender)
        val vehicleRepository = VehicleRepositoryI()
        val assignmentRepository = VehicleAssignmentRepositoryI()
        val membershipRepository = CompanyMembershipRepositoryI()
        val assignmentService =
            VehicleAssignmentServiceI(
                vehicleAssignmentRepository = assignmentRepository,
                vehicleRepository = vehicleRepository,
                membershipRepository = membershipRepository,
                syncService = FakeSyncService(),
                hub = NoOpVehicleLocationHub(),
                tx = tx,
            )
        val service =
            VehiclePingServiceI(
                pingRepository = pingRepository,
                vehicleRepository = vehicleRepository,
                vehicleAssignmentService = assignmentService,
                pingHub = hub,
                deviceInstallationService = deviceInstallationService,
                cellIndexer = H3CellIndexer(resolution = PING_LOCATION_RESOLUTION),
                tx = tx,
            )
        block(VehiclePingTestHarness(service, pingRepository, hub, pushSender, tx))
    }
}
