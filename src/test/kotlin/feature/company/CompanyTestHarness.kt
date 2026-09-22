package com.mozgobolt.feature.company

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.company.data.repository.CompanyMembershipRepositoryI
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.service.CompanyServiceI
import com.mozgobolt.feature.sync.routing.FakeSyncService
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import com.mozgobolt.feature.vehicleAssignment.data.repository.VehicleAssignmentRepositoryI
import com.mozgobolt.feature.vehicleAssignment.service.VehicleAssignmentServiceI
import com.mozgobolt.feature.vehicleTracking.service.H3CellIndexer
import com.mozgobolt.feature.vehicleTracking.service.InMemoryVehicleLocationHub

data class CompanyTestHarness(
    val service: CompanyServiceI,
    val membershipRepository: CompanyMembershipRepositoryI,
    val tx: TransactionalRunner,
)

fun withRealCompanyDatabase(block: suspend (CompanyTestHarness) -> Unit) {
    withRealDatabase { _, tx ->
        val membershipRepository = CompanyMembershipRepositoryI()
        val vehicleAssignmentService =
            VehicleAssignmentServiceI(
                vehicleAssignmentRepository = VehicleAssignmentRepositoryI(),
                vehicleRepository = VehicleRepositoryI(),
                membershipRepository = membershipRepository,
                syncService = FakeSyncService(),
                hub = InMemoryVehicleLocationHub(H3CellIndexer()),
                tx = tx,
            )
        val service =
            CompanyServiceI(
                companyRepository = CompanyRepositoryI(),
                membershipRepository = membershipRepository,
                vehicleAssignmentService = vehicleAssignmentService,
                syncService = FakeSyncService(),
                tx = tx,
            )
        block(CompanyTestHarness(service, membershipRepository, tx))
    }
}
