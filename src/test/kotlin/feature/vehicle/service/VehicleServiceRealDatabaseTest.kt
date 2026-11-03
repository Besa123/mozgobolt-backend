/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicle.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.company.data.repository.CompanyMembershipRepositoryI
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.CompanyRepository
import com.mozgobolt.feature.company.domain.model.Company
import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.sync.domain.SyncService
import com.mozgobolt.feature.sync.domain.model.SyncChange
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.sync.routing.FakeSyncService
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import com.mozgobolt.feature.vehicle.domain.model.VehicleError
import com.mozgobolt.feature.vehicleAssignment.data.repository.VehicleAssignmentRepositoryI
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignmentError
import com.mozgobolt.feature.vehicleAssignment.service.VehicleAssignmentServiceI
import com.mozgobolt.feature.vehicleTracking.NoOpVehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class VehicleServiceRealDatabaseTest {
    private class RecordingVehicleLocationHub : VehicleLocationHub by NoOpVehicleLocationHub() {
        val markedOffline = mutableListOf<Int>()

        override fun markOffline(vehicleId: Int) {
            markedOffline += vehicleId
        }

        override fun markOffline(
            vehicleId: Int,
            assignmentId: Int,
        ): Boolean {
            markedOffline += vehicleId
            return true
        }
    }

    private class FailingSyncService : SyncService by FakeSyncService() {
        override suspend fun recordChanges(changes: List<SyncChange>) {
            error("simulated sync persistence failure")
        }
    }

    @Test
    fun `concurrent updates to the same plate serialize and return one conflict`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val companyMembershipRepository = CompanyMembershipRepositoryI()
            val baseCompanyRepository = CompanyRepositoryI()
            val vehicleRepository = VehicleRepositoryI()
            val (companyId, firstVehicleId, secondVehicleId) =
                tx.transactional {
                    val company = createCompany(baseCompanyRepository)
                    companyMembershipRepository.addIfAbsent(company.id, 1, CompanyRole.ADMIN)
                    val first = checkNotNull(vehicleRepository.create(company.id, "First truck", "PLATE-FIRST"))
                    val second = checkNotNull(vehicleRepository.create(company.id, "Second truck", "PLATE-SECOND"))
                    Triple(company.id, first.id, second.id)
                }

            val companyRepository = coordinatedCompanyRepository(baseCompanyRepository)
            val syncService = FakeSyncService()
            val service =
                newVehicleService(
                    companyRepository,
                    vehicleRepository,
                    companyMembershipRepository,
                    FakeVehicleAssignmentService(),
                    syncService,
                    tx,
                )
            val outcomes = mutableListOf<AppResult<com.mozgobolt.feature.vehicle.domain.model.Vehicle, VehicleError>>()

            coroutineScope {
                val first =
                    async(Dispatchers.IO) {
                        service.updateVehicle(1, companyId, firstVehicleId, "RACE-PLATE", null)
                    }
                withTimeout(5_000) { companyRepository.firstLockAcquired.await() }

                val second =
                    async(Dispatchers.IO) {
                        service.updateVehicle(1, companyId, secondVehicleId, "RACE-PLATE", null)
                    }
                try {
                    withTimeout(5_000) { companyRepository.secondLockRequested.await() }
                    assertEquals(
                        null,
                        withTimeoutOrNull(250) { companyRepository.secondLockAcquired.await() },
                        "the second plate update must wait for the first company-row lock",
                    )
                } finally {
                    companyRepository.releaseFirstLock.complete(Unit)
                }

                outcomes += listOf(first.await(), second.await())
            }

            assertEquals(1, outcomes.count { it is AppResult.Success })
            assertEquals(
                1,
                outcomes.count { it is AppResult.Error && it.errorType == VehicleError.LICENSE_PLATE_TAKEN },
            )
            val fleet = tx.transactional { vehicleRepository.findAllByCompany(companyId) }
            assertEquals(2, fleet.size)
            assertEquals(1, fleet.count { it.licensePlate == "RACE-PLATE" })
            assertEquals(
                1,
                syncService.recorded.count {
                    it.entityType == SyncEntityType.VEHICLE && it.operation == SyncOperation.UPSERT
                },
                "only the successful update may emit a sync event",
            )
        }
    }

    @Test
    fun `archiving a vehicle with an active assignment ends the assignment in PostgreSQL`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val companyRepository = CompanyRepositoryI()
            val companyMembershipRepository = CompanyMembershipRepositoryI()
            val vehicleRepository = VehicleRepositoryI()
            val assignmentRepository = VehicleAssignmentRepositoryI()
            val syncService = FakeSyncService()
            val (companyId, vehicleId) =
                tx.transactional {
                    val company = createCompany(companyRepository)
                    companyMembershipRepository.addIfAbsent(company.id, 1, CompanyRole.ADMIN)
                    companyMembershipRepository.addIfAbsent(company.id, 2, CompanyRole.MEMBER)
                    val vehicle = checkNotNull(vehicleRepository.create(company.id, "Shift truck", "SHIFT-PLATE"))
                    company.id to vehicle.id
                }
            val assignmentService =
                VehicleAssignmentServiceI(
                    vehicleAssignmentRepository = assignmentRepository,
                    companyRepository = companyRepository,
                    vehicleRepository = vehicleRepository,
                    membershipRepository = companyMembershipRepository,
                    syncService = syncService,
                    hub = NoOpVehicleLocationHub(),
                    tx = tx,
                )
            assignmentService
                .link(vendorUserId = 2, vehicleId = vehicleId)
                .fold(onSuccess = {}, onError = { fail("setup assignment failed: $it") })
            val vehicleService =
                newVehicleService(
                    companyRepository,
                    vehicleRepository,
                    companyMembershipRepository,
                    assignmentService,
                    syncService,
                    tx,
                )

            vehicleService
                .archiveVehicle(adminUserId = 1, companyId = companyId, vehicleId = vehicleId)
                .fold(onSuccess = {}, onError = { fail("archive failed: $it") })

            assertNotNull(tx.transactional { vehicleRepository.findById(vehicleId)?.archivedAt })
            assertNull(tx.transactional { assignmentRepository.findActiveForVehicle(vehicleId) })
            assertNull(tx.transactional { assignmentRepository.findActiveForVendor(2) })
            assertTrue(
                syncService.recorded.any {
                    it.entityType == SyncEntityType.VEHICLE_ASSIGNMENT && it.operation == SyncOperation.UPSERT
                },
            )
            assertTrue(
                syncService.recorded.any {
                    it.entityType == SyncEntityType.VEHICLE &&
                        it.entityId == vehicleId &&
                        it.operation == SyncOperation.UPSERT
                },
            )
        }
    }

    @Test
    fun `a link waiting behind vehicle archive cannot create an active assignment`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val baseCompanyRepository = CompanyRepositoryI()
            val companyMembershipRepository = CompanyMembershipRepositoryI()
            val vehicleRepository = VehicleRepositoryI()
            val assignmentRepository = VehicleAssignmentRepositoryI()
            val (companyId, vehicleId) =
                tx.transactional {
                    val company = createCompany(baseCompanyRepository)
                    companyMembershipRepository.addIfAbsent(company.id, 1, CompanyRole.ADMIN)
                    companyMembershipRepository.addIfAbsent(company.id, 2, CompanyRole.MEMBER)
                    val vehicle = checkNotNull(vehicleRepository.create(company.id, "Race truck", "RACE-ARCHIVE"))
                    company.id to vehicle.id
                }
            val coordinatedCompanyRepository = coordinatedCompanyRepository(baseCompanyRepository)
            val syncService = FakeSyncService()
            val assignmentService =
                VehicleAssignmentServiceI(
                    vehicleAssignmentRepository = assignmentRepository,
                    companyRepository = coordinatedCompanyRepository,
                    vehicleRepository = vehicleRepository,
                    membershipRepository = companyMembershipRepository,
                    syncService = syncService,
                    hub = NoOpVehicleLocationHub(),
                    tx = tx,
                )
            val vehicleService =
                newVehicleService(
                    coordinatedCompanyRepository,
                    vehicleRepository,
                    companyMembershipRepository,
                    assignmentService,
                    syncService,
                    tx,
                )

            val (archiveResult, linkResult) =
                coroutineScope {
                    val archive =
                        async(Dispatchers.IO) {
                            vehicleService.archiveVehicle(1, companyId, vehicleId)
                        }
                    withTimeout(5_000) { coordinatedCompanyRepository.firstLockAcquired.await() }

                    val link =
                        async(Dispatchers.IO) {
                            assignmentService.link(vendorUserId = 2, vehicleId = vehicleId)
                        }
                    try {
                        withTimeout(5_000) { coordinatedCompanyRepository.secondLockRequested.await() }
                        assertEquals(
                            null,
                            withTimeoutOrNull(250) { coordinatedCompanyRepository.secondLockAcquired.await() },
                            "the link must wait until the archive transaction commits",
                        )
                    } finally {
                        coordinatedCompanyRepository.releaseFirstLock.complete(Unit)
                    }

                    archive.await() to link.await()
                }

            archiveResult.fold(onSuccess = {}, onError = { fail("archive failed: $it") })
            linkResult.fold(
                onSuccess = { fail("an archived vehicle must not receive a new assignment") },
                onError = { assertEquals(VehicleAssignmentError.VEHICLE_ARCHIVED, it) },
            )
            assertNotNull(tx.transactional { vehicleRepository.findById(vehicleId)?.archivedAt })
            assertNull(tx.transactional { assignmentRepository.findActiveForVehicle(vehicleId) })
        }
    }

    @Test
    fun `sync failure during archive rolls back assignment and does not emit offline`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val companyRepository = CompanyRepositoryI()
            val membershipRepository = CompanyMembershipRepositoryI()
            val vehicleRepository = VehicleRepositoryI()
            val assignmentRepository = VehicleAssignmentRepositoryI()
            val (companyId, vehicleId) =
                tx.transactional {
                    val company = createCompany(companyRepository)
                    membershipRepository.addIfAbsent(company.id, 1, CompanyRole.ADMIN)
                    membershipRepository.addIfAbsent(company.id, 2, CompanyRole.MEMBER)
                    val vehicle =
                        checkNotNull(vehicleRepository.create(company.id, "Rollback truck", "ROLLBACK-ARCHIVE"))
                    company.id to vehicle.id
                }
            val hub = RecordingVehicleLocationHub()
            val assignmentService =
                VehicleAssignmentServiceI(
                    vehicleAssignmentRepository = assignmentRepository,
                    companyRepository = companyRepository,
                    vehicleRepository = vehicleRepository,
                    membershipRepository = membershipRepository,
                    syncService = FakeSyncService(),
                    hub = hub,
                    tx = tx,
                )
            assignmentService
                .link(vendorUserId = 2, vehicleId = vehicleId)
                .fold(onSuccess = {}, onError = { fail("setup assignment failed: $it") })
            val vehicleService =
                newVehicleService(
                    companyRepository,
                    vehicleRepository,
                    membershipRepository,
                    assignmentService,
                    FailingSyncService(),
                    tx,
                )

            assertFailsWith<IllegalStateException> {
                vehicleService.archiveVehicle(adminUserId = 1, companyId = companyId, vehicleId = vehicleId)
            }

            assertNull(tx.transactional { vehicleRepository.findById(vehicleId)?.archivedAt })
            assertNotNull(tx.transactional { assignmentRepository.findActiveForVehicle(vehicleId) })
            assertTrue(hub.markedOffline.isEmpty(), "a rolled-back archive must not clear the live vehicle")
        }
    }

    @Suppress("LongParameterList")
    private fun newVehicleService(
        companyRepository: CompanyRepository,
        vehicleRepository: VehicleRepositoryI,
        membershipRepository: CompanyMembershipRepositoryI,
        assignmentService: com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService,
        syncService: SyncService,
        tx: TransactionalRunner,
    ) = VehicleServiceI(
        companyRepository = companyRepository,
        vehicleRepository = vehicleRepository,
        membershipRepository = membershipRepository,
        vehicleAssignmentService = assignmentService,
        syncService = syncService,
        imageSanitizer = FakeImageSanitizer(),
        virusScanner = FakeVirusScanner(),
        imageStorage = FakeImageStorage(),
        tx = tx,
    )

    private suspend fun createCompany(repository: CompanyRepository): Company =
        repository.create(
            name = "Vehicle State Company",
            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
        )

    private fun coordinatedCompanyRepository(delegate: CompanyRepositoryI) =
        CoordinatedCompanyRepository(
            delegate = delegate,
            firstLockAcquired = CompletableDeferred(),
            secondLockRequested = CompletableDeferred(),
            secondLockAcquired = CompletableDeferred(),
            releaseFirstLock = CompletableDeferred(),
        )

    private class CoordinatedCompanyRepository(
        private val delegate: CompanyRepository,
        val firstLockAcquired: CompletableDeferred<Unit>,
        val secondLockRequested: CompletableDeferred<Unit>,
        val secondLockAcquired: CompletableDeferred<Unit>,
        val releaseFirstLock: CompletableDeferred<Unit>,
    ) : CompanyRepository by delegate {
        private val lockCalls = AtomicInteger()

        override suspend fun findByIdForUpdate(companyId: Int): Company? {
            val callNumber = lockCalls.incrementAndGet()
            if (callNumber == 2) secondLockRequested.complete(Unit)

            val company = delegate.findByIdForUpdate(companyId)
            when (callNumber) {
                1 -> {
                    firstLockAcquired.complete(Unit)
                    releaseFirstLock.await()
                }

                2 -> {
                    secondLockAcquired.complete(Unit)
                }
            }
            return company
        }
    }
}
