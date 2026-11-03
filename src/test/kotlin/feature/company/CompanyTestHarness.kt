/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.company

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.company.data.repository.CompanyMembershipRepositoryI
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.service.CompanyServiceI
import com.mozgobolt.feature.companyFavorite.data.repository.CompanyFavoriteRepositoryI
import com.mozgobolt.feature.sync.routing.FakeSyncService
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import com.mozgobolt.feature.vehicleAssignment.data.repository.VehicleAssignmentRepositoryI
import com.mozgobolt.feature.vehicleAssignment.service.VehicleAssignmentServiceI
import com.mozgobolt.feature.vehicleTracking.service.H3CellIndexer
import com.mozgobolt.feature.vehicleTracking.service.InMemoryVehicleLocationHub

data class CompanyTestHarness(
    val service: CompanyServiceI,
    val membershipRepository: CompanyMembershipRepositoryI,
    val syncService: FakeSyncService,
    val tx: TransactionalRunner,
)

fun withRealCompanyDatabase(block: suspend (CompanyTestHarness) -> Unit) {
    withRealDatabase { _, tx ->
        val companyRepository = CompanyRepositoryI()
        val membershipRepository = CompanyMembershipRepositoryI()
        val vehicleAssignmentService =
            VehicleAssignmentServiceI(
                vehicleAssignmentRepository = VehicleAssignmentRepositoryI(),
                companyRepository = companyRepository,
                vehicleRepository = VehicleRepositoryI(),
                membershipRepository = membershipRepository,
                syncService = FakeSyncService(),
                hub = InMemoryVehicleLocationHub(H3CellIndexer()),
                tx = tx,
            )
        val companyServiceSyncService = FakeSyncService()
        val service =
            CompanyServiceI(
                companyRepository = companyRepository,
                membershipRepository = membershipRepository,
                companyFavoriteRepository = CompanyFavoriteRepositoryI(),
                vehicleAssignmentService = vehicleAssignmentService,
                syncService = companyServiceSyncService,
                tx = tx,
            )
        block(CompanyTestHarness(service, membershipRepository, companyServiceSyncService, tx))
    }
}
