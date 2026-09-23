package com.mozgobolt.feature.vehicleAssignment

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.company.data.repository.CompanyMembershipRepositoryI
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.sync.routing.FakeSyncService
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import com.mozgobolt.feature.vehicleAssignment.data.repository.VehicleAssignmentRepositoryI
import com.mozgobolt.feature.vehicleAssignment.service.VehicleAssignmentServiceI
import com.mozgobolt.feature.vehicleTracking.NoOpVehicleLocationHub

data class VehicleAssignmentTestHarness(
    val service: VehicleAssignmentServiceI,
    val companyRepository: CompanyRepositoryI,
    val membershipRepository: CompanyMembershipRepositoryI,
    val vehicleRepository: VehicleRepositoryI,
    val assignmentRepository: VehicleAssignmentRepositoryI,
    val tx: TransactionalRunner,
) {
    /** Puts both of [withRealDatabase]'s seeded users (ids 1 and 2) into the same company, as
     * plain members, and returns one vehicle in it — the shared starting point every test in this
     * harness needs, so a same-vehicle or same-vendor race can actually be set up. */
    suspend fun seedSharedCompanyWithOneVehicle(): Int =
        tx.transactional {
            val company =
                companyRepository.create(
                    name = "Shared Co",
                    inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                )
            membershipRepository.addIfAbsent(company.id, 1, CompanyRole.MEMBER)
            membershipRepository.addIfAbsent(company.id, 2, CompanyRole.MEMBER)
            vehicleRepository.create(companyId = company.id, label = "Shared Truck", licensePlate = "SHARED-1")!!.id
        }

    /** Same shared company as [seedSharedCompanyWithOneVehicle], but with two vehicles — needed to
     * set up "already actively driving vehicle A, now racing for vehicle B" scenarios. */
    suspend fun seedSharedCompanyWithTwoVehicles(): Pair<Int, Int> =
        tx.transactional {
            val company =
                companyRepository.create(
                    name = "Shared Co",
                    inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                )
            membershipRepository.addIfAbsent(company.id, 1, CompanyRole.MEMBER)
            membershipRepository.addIfAbsent(company.id, 2, CompanyRole.MEMBER)
            val vehicleA =
                vehicleRepository
                    .create(
                        companyId = company.id,
                        label = "Truck A",
                        licensePlate = "SHARED-A",
                    )!!
                    .id
            val vehicleB =
                vehicleRepository
                    .create(
                        companyId = company.id,
                        label = "Truck B",
                        licensePlate = "SHARED-B",
                    )!!
                    .id
            vehicleA to vehicleB
        }
}

fun withRealVehicleAssignmentDatabase(block: suspend (VehicleAssignmentTestHarness) -> Unit) {
    withRealDatabase { _, tx ->
        val companyRepository = CompanyRepositoryI()
        val membershipRepository = CompanyMembershipRepositoryI()
        val vehicleRepository = VehicleRepositoryI()
        val assignmentRepository = VehicleAssignmentRepositoryI()
        val service =
            VehicleAssignmentServiceI(
                vehicleAssignmentRepository = assignmentRepository,
                vehicleRepository = vehicleRepository,
                membershipRepository = membershipRepository,
                syncService = FakeSyncService(),
                hub = NoOpVehicleLocationHub(),
                tx = tx,
            )
        block(
            VehicleAssignmentTestHarness(
                service = service,
                companyRepository = companyRepository,
                membershipRepository = membershipRepository,
                vehicleRepository = vehicleRepository,
                assignmentRepository = assignmentRepository,
                tx = tx,
            ),
        )
    }
}
