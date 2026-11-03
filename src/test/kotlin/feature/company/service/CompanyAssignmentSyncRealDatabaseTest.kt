/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.company.service

import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.feature.company.CompanyTestHarness
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.CompanyRepository
import com.mozgobolt.feature.company.withRealCompanyDatabase
import com.mozgobolt.feature.companyFavorite.data.repository.CompanyFavoriteRepositoryI
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.sync.routing.FakeSyncService
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import com.mozgobolt.feature.vehicleAssignment.data.repository.VehicleAssignmentRepositoryI
import com.mozgobolt.feature.vehicleAssignment.service.VehicleAssignmentServiceI
import com.mozgobolt.feature.vehicleTracking.NoOpVehicleLocationHub
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompanyAssignmentSyncRealDatabaseTest {
    private data class AssignmentRaceSetup(
        val companyToDeleteId: Int,
        val oldVehicleId: Int,
        val newVehicleId: Int,
        val oldAssignmentId: Int,
    )

    private data class AssignmentRaceServices(
        val syncService: FakeSyncService,
        val assignmentService: VehicleAssignmentServiceI,
        val companyService: CompanyServiceI,
    )

    @Test
    fun `deleting a company syncs ended assignments owned by former members`() {
        skipIfNoDocker()

        withRealCompanyDatabase { harness ->
            val company =
                harness.service.createCompany(creatorUserId = 1, name = "Former Member Assignment Company").fold(
                    onSuccess = { it },
                    onError = { error("setup failed: $it") },
                )
            harness.service.joinCompany(userId = 2, inviteCode = company.inviteCode).fold(
                onSuccess = {},
                onError = { error("setup failed: $it") },
            )

            val assignmentId =
                harness.tx.transactional {
                    val vehicle =
                        checkNotNull(
                            VehicleRepositoryI().create(
                                companyId = company.id,
                                label = "Former Member Truck",
                                licensePlate = "FORMER-ASSIGN",
                            ),
                        )
                    val assignment =
                        checkNotNull(
                            VehicleAssignmentRepositoryI().startAssignment(
                                vehicleId = vehicle.id,
                                vendorUserId = 2,
                                startedAt = Instant.parse("2026-01-01T00:00:00Z"),
                            ),
                        )
                    check(
                        VehicleAssignmentRepositoryI()
                            .endAssignment(assignment.id, Instant.parse("2026-01-01T01:00:00Z")),
                    )
                    assignment.id
                }

            harness.service.leaveCompany(userId = 2, companyId = company.id).fold(
                onSuccess = {},
                onError = { error("former member leave failed: $it") },
            )
            harness.service.deleteCompany(adminUserId = 1, companyId = company.id).fold(
                onSuccess = {},
                onError = { error("delete failed: $it") },
            )

            val assignmentDeleteEvents =
                harness.syncService.recorded.filter {
                    it.entityType == SyncEntityType.VEHICLE_ASSIGNMENT && it.operation == SyncOperation.DELETE
                }
            assertEquals(listOf(2 to assignmentId), assignmentDeleteEvents.map { it.userId to it.entityId })
        }
    }

    @Test
    fun `link racing company deletion cannot leave a deleted assignment as a later sync upsert`() {
        skipIfNoDocker()

        withRealCompanyDatabase { harness ->
            val setup = seedAssignmentRace(harness)

            val deleteLockAcquired = CompletableDeferred<Unit>()
            val allowDeletionToContinue = CompletableDeferred<Unit>()
            val companyRepository =
                BlockingCompanyRepository(
                    delegate = CompanyRepositoryI(),
                    blockedCompanyId = setup.companyToDeleteId,
                    lockAcquired = deleteLockAcquired,
                    releaseLock = allowDeletionToContinue,
                )
            val services = createRaceServices(harness, companyRepository)

            coroutineScope {
                val deletion =
                    async(Dispatchers.IO) {
                        services.companyService.deleteCompany(adminUserId = 1, companyId = setup.companyToDeleteId)
                    }
                withTimeout(5_000) { deleteLockAcquired.await() }

                try {
                    val link =
                        async(Dispatchers.IO) {
                            services.assignmentService.link(vendorUserId = 1, vehicleId = setup.newVehicleId)
                        }
                    withTimeout(5_000) { link.await() }.fold(
                        onSuccess = {},
                        onError = { error("link to the surviving company failed: $it") },
                    )
                } finally {
                    allowDeletionToContinue.complete(Unit)
                }

                deletion.await().fold(onSuccess = {}, onError = { error("company delete failed: $it") })
            }

            val oldAssignmentOperations =
                services.syncService.recorded
                    .filter {
                        it.userId == 1 &&
                            it.entityType == SyncEntityType.VEHICLE_ASSIGNMENT &&
                            it.entityId == setup.oldAssignmentId
                    }.map { it.operation }
            assertEquals(
                listOf(SyncOperation.UPSERT, SyncOperation.DELETE),
                oldAssignmentOperations,
            )
            assertEquals(
                setup.newVehicleId,
                harness.tx.transactional {
                    VehicleAssignmentRepositoryI().findActiveForVendor(1)?.vehicleId
                },
            )
            assertNull(harness.tx.transactional { VehicleRepositoryI().findById(setup.oldVehicleId) })
            assertEquals(
                0L,
                harness.tx.transactional {
                    VehicleAssignmentRepositoryI().findAllForCompany(setup.companyToDeleteId).count().toLong()
                },
            )
        }
    }

    private suspend fun seedAssignmentRace(harness: CompanyTestHarness): AssignmentRaceSetup {
        val companyToDelete =
            harness.service.createCompany(creatorUserId = 1, name = "Assignment Race Company").fold(
                onSuccess = { it },
                onError = { error("setup failed: $it") },
            )
        val survivingCompany =
            harness.service.createCompany(creatorUserId = 2, name = "Surviving Assignment Company").fold(
                onSuccess = { it },
                onError = { error("setup failed: $it") },
            )
        harness.service.joinCompany(userId = 1, inviteCode = survivingCompany.inviteCode).fold(
            onSuccess = {},
            onError = { error("setup failed: $it") },
        )

        return harness.tx.transactional {
            val vehicleRepository = VehicleRepositoryI()
            val assignmentRepository = VehicleAssignmentRepositoryI()
            val oldVehicle =
                checkNotNull(
                    vehicleRepository.create(companyToDelete.id, "Old vehicle", "ASSIGNMENT-RACE-OLD"),
                )
            val newVehicle =
                checkNotNull(
                    vehicleRepository.create(survivingCompany.id, "New vehicle", "ASSIGNMENT-RACE-NEW"),
                )
            val assignment =
                checkNotNull(
                    assignmentRepository.startAssignment(
                        oldVehicle.id,
                        vendorUserId = 1,
                        startedAt = Instant.now(),
                    ),
                )
            AssignmentRaceSetup(companyToDelete.id, oldVehicle.id, newVehicle.id, assignment.id)
        }
    }

    private fun createRaceServices(
        harness: CompanyTestHarness,
        companyRepository: BlockingCompanyRepository,
    ): AssignmentRaceServices {
        val syncService = FakeSyncService()
        val assignmentService =
            VehicleAssignmentServiceI(
                vehicleAssignmentRepository = VehicleAssignmentRepositoryI(),
                companyRepository = companyRepository,
                vehicleRepository = VehicleRepositoryI(),
                membershipRepository = harness.membershipRepository,
                syncService = syncService,
                hub = NoOpVehicleLocationHub(),
                tx = harness.tx,
            )
        val companyService =
            CompanyServiceI(
                companyRepository = companyRepository,
                membershipRepository = harness.membershipRepository,
                companyFavoriteRepository = CompanyFavoriteRepositoryI(),
                vehicleAssignmentService = assignmentService,
                syncService = syncService,
                tx = harness.tx,
            )

        return AssignmentRaceServices(syncService, assignmentService, companyService)
    }

    private class BlockingCompanyRepository(
        private val delegate: com.mozgobolt.feature.company.domain.CompanyRepository,
        private val blockedCompanyId: Int,
        private val lockAcquired: CompletableDeferred<Unit>,
        private val releaseLock: CompletableDeferred<Unit>,
    ) : com.mozgobolt.feature.company.domain.CompanyRepository by delegate {
        private val firstLock =
            java.util.concurrent.atomic
                .AtomicBoolean(true)

        override suspend fun findByIdForUpdate(companyId: Int) =
            delegate.findByIdForUpdate(companyId).also { company ->
                if (companyId == blockedCompanyId && firstLock.compareAndSet(true, false)) {
                    lockAcquired.complete(Unit)
                    releaseLock.await()
                }
            }
    }
}
