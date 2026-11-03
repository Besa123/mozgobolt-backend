/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.company.service

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.feature.company.CompanyTestHarness
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.CompanyRepository
import com.mozgobolt.feature.company.domain.model.Company
import com.mozgobolt.feature.company.domain.model.CompanyError
import com.mozgobolt.feature.company.domain.model.CompanyMemberSummary
import com.mozgobolt.feature.company.domain.model.CompanyMembershipSummary
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.company.withRealCompanyDatabase
import com.mozgobolt.feature.companyFavorite.data.repository.CompanyFavoriteRepositoryI
import com.mozgobolt.feature.proximityNotification.data.repository.ProximityNotificationRepositoryI
import com.mozgobolt.feature.savedLocation.data.repository.SavedLocationRepositoryI
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.sync.routing.FakeSyncService
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import com.mozgobolt.feature.vehicleAssignment.data.database.VehicleAssignmentsTable
import com.mozgobolt.feature.vehicleAssignment.data.repository.VehicleAssignmentRepositoryI
import com.mozgobolt.feature.vehicleAssignment.service.VehicleAssignmentServiceI
import com.mozgobolt.feature.vehiclePing.data.database.VehiclePingsTable
import com.mozgobolt.feature.vehicleTracking.data.database.VehicleLocationsTable
import com.mozgobolt.feature.vehicleTracking.data.repository.VehicleLocationRepositoryI
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import com.mozgobolt.feature.vehicleTracking.service.H3CellIndexer
import com.mozgobolt.feature.vehicleTracking.service.InMemoryVehicleLocationHub
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The unit tests in [CompanyServiceITest] prove the *service* correctly reacts to
 * [com.mozgobolt.feature.company.domain.CompanyMembershipRepository.addIfAbsent] returning null;
 * this proves the underlying assumption those tests are built on — that a real, concurrent
 * Postgres workload actually produces that outcome via the `UNIQUE(company_id, user_id)` index —
 * rather than trusting it by reasoning alone. Exposed throws outside a transaction in ways
 * invisible to fakes; this is only caught by a real DB (see CLAUDE.md's own note on exactly this
 * class of bug).
 */
class CompanyServiceRealDatabaseTest {
    private data class CompanyDeleteFixture(
        val companyId: Int,
        val survivingCompanyId: Int,
        val vehicleId: Int,
        val assignmentId: Int,
        val savedLocationId: Int,
    )

    @Test
    fun `company member list includes each member's display name and role`() {
        skipIfNoDocker()

        withRealCompanyDatabase { harness ->
            val company =
                harness.service.createCompany(creatorUserId = 1, name = "FamilyFrost").fold(
                    onSuccess = { it },
                    onError = { error("setup failed: $it") },
                )
            harness.service.joinCompany(userId = 2, inviteCode = company.inviteCode).fold(
                onSuccess = {},
                onError = { error("setup failed: $it") },
            )

            val members =
                harness.service.listMembers(userId = 1, companyId = company.id).fold(
                    onSuccess = { it },
                    onError = { error("member listing failed: $it") },
                )

            assertEquals(
                listOf(
                    CompanyMemberSummary(1, "Test User One", CompanyRole.ADMIN),
                    CompanyMemberSummary(2, "Test User Two", CompanyRole.MEMBER),
                ),
                members,
            )
        }
    }

    @Test
    fun `deleting a company cascades its memberships but preserves other memberships for the same user`() {
        skipIfNoDocker()

        withRealCompanyDatabase { harness ->
            val companyToDelete =
                harness.service.createCompany(creatorUserId = 1, name = "Deleted Company").fold(
                    onSuccess = { it },
                    onError = { error("setup failed: $it") },
                )
            val survivingCompany =
                harness.service.createCompany(creatorUserId = 2, name = "Surviving Company").fold(
                    onSuccess = { it },
                    onError = { error("setup failed: $it") },
                )
            harness.service.joinCompany(userId = 1, inviteCode = survivingCompany.inviteCode).fold(
                onSuccess = {},
                onError = { error("setup failed: $it") },
            )

            harness.service.deleteCompany(adminUserId = 1, companyId = companyToDelete.id).fold(
                onSuccess = {},
                onError = { error("delete failed: $it") },
            )

            assertNull(harness.tx.transactional { harness.membershipRepository.find(companyToDelete.id, 1) })
            assertNotNull(harness.tx.transactional { harness.membershipRepository.find(survivingCompany.id, 1) })
            assertEquals(
                listOf(
                    CompanyMembershipSummary(
                        companyId = survivingCompany.id,
                        companyName = "Surviving Company",
                        membershipRole = CompanyRole.MEMBER,
                    ),
                ),
                harness.service.listCompaniesForUser(1).fold(
                    onSuccess = { it },
                    onError = { error("company listing failed: $it") },
                ),
            )
        }
    }

    @Test
    fun `deleting a company cascades its fleet session telemetry and favorites without deleting user data`() {
        skipIfNoDocker()

        withRealCompanyDatabase { harness ->
            val fixture = seedCompanyDeletion(harness)
            harness.service.deleteCompany(adminUserId = 1, companyId = fixture.companyId).fold(
                onSuccess = {},
                onError = { error("delete failed: $it") },
            )
            assertCompanyDeletionEffects(harness, fixture)
        }
    }

    @Test
    fun `listing companies reads the user's memberships and roles from the database`() {
        skipIfNoDocker()

        withRealCompanyDatabase { harness ->
            val adminCompany =
                harness.service.createCompany(creatorUserId = 1, name = "Admin Company").fold(
                    onSuccess = { it },
                    onError = { error("setup failed: $it") },
                )
            val memberCompany =
                harness.service.createCompany(creatorUserId = 2, name = "Member Company").fold(
                    onSuccess = { it },
                    onError = { error("setup failed: $it") },
                )
            harness.service.joinCompany(userId = 1, inviteCode = memberCompany.inviteCode).fold(
                onSuccess = {},
                onError = { error("setup failed: $it") },
            )

            val companies =
                harness.service.listCompaniesForUser(1).fold(
                    onSuccess = { it },
                    onError = { error("company listing failed: $it") },
                )

            assertEquals(
                listOf(
                    CompanyMembershipSummary(adminCompany.id, "Admin Company", CompanyRole.ADMIN),
                    CompanyMembershipSummary(memberCompany.id, "Member Company", CompanyRole.MEMBER),
                ),
                companies,
            )
        }
    }

    @Test
    fun `only company admins can read the current invite code from PostgreSQL`() {
        skipIfNoDocker()

        withRealCompanyDatabase { harness ->
            val company =
                harness.service.createCompany(creatorUserId = 1, name = "Invite Code Company").fold(
                    onSuccess = { it },
                    onError = { error("company creation failed: $it") },
                )
            harness.service.joinCompany(userId = 2, inviteCode = company.inviteCode).fold(
                onSuccess = {},
                onError = { error("company join failed: $it") },
            )

            val adminCode =
                harness.service.getCurrentInviteCode(adminUserId = 1, companyId = company.id).fold(
                    onSuccess = { it },
                    onError = { error("admin should read the current invite code: $it") },
                )
            assertEquals(company.inviteCode, adminCode)

            val memberResult =
                harness.service.getCurrentInviteCode(adminUserId = 2, companyId = company.id).fold(
                    onSuccess = { error("a regular member must not read the invite code") },
                    onError = { it },
                )
            assertEquals(CompanyError.NOT_ADMIN, memberResult)

            val nonMemberResult =
                harness.service.getCurrentInviteCode(adminUserId = 3, companyId = company.id).fold(
                    onSuccess = { error("a non-member must not read the invite code") },
                    onError = { it },
                )
            assertEquals(CompanyError.NOT_A_MEMBER, nonMemberResult)
        }
    }

    @Test
    fun `two concurrent joins with the same invite code for the same user only ever produce one winner`() {
        skipIfNoDocker()

        withRealCompanyDatabase { harness ->
            val company =
                harness.service.createCompany(creatorUserId = 1, name = "FamilyFrost").fold(
                    onSuccess = { it },
                    onError = { error("setup failed: $it") },
                )

            val results =
                coroutineScope {
                    val first = async { harness.service.joinCompany(userId = 2, inviteCode = company.inviteCode) }
                    val second = async { harness.service.joinCompany(userId = 2, inviteCode = company.inviteCode) }
                    listOf(first.await(), second.await())
                }

            val successes = results.count { it is AppResult.Success }
            val alreadyMember =
                results.count { it is AppResult.Error && it.errorType == CompanyError.ALREADY_MEMBER }
            assertEquals(1, successes, "exactly one concurrent join should win: $results")
            assertEquals(1, alreadyMember, "the loser must see a clean ALREADY_MEMBER, not crash: $results")

            val membership = harness.tx.transactional { harness.membershipRepository.find(company.id, 2) }
            assertNotNull(membership, "the user must end up a member exactly once, regardless of which call won")
        }
    }

    @Test
    fun `concurrent admin demotion and self-removal serialize so the company retains an admin`() {
        skipIfNoDocker()

        withRealCompanyDatabase { harness ->
            val company =
                harness.service.createCompany(creatorUserId = 1, name = "FamilyFrost").fold(
                    onSuccess = { it },
                    onError = { error("setup failed: $it") },
                )
            harness.service.joinCompany(userId = 2, inviteCode = company.inviteCode).fold(
                onSuccess = {},
                onError = { error("setup failed: $it") },
            )
            harness.service.promoteMember(adminUserId = 1, companyId = company.id, targetUserId = 2).fold(
                onSuccess = {},
                onError = { error("setup failed: $it") },
            )

            val firstLockAcquired = CompletableDeferred<Unit>()
            val secondLockRequested = CompletableDeferred<Unit>()
            val secondLockAcquired = CompletableDeferred<Unit>()
            val releaseFirstLock = CompletableDeferred<Unit>()
            val companyRepository =
                CoordinatedCompanyRepository(
                    delegate = CompanyRepositoryI(),
                    firstLockAcquired = firstLockAcquired,
                    secondLockRequested = secondLockRequested,
                    secondLockAcquired = secondLockAcquired,
                    releaseFirstLock = releaseFirstLock,
                )
            val vehicleAssignmentService =
                VehicleAssignmentServiceI(
                    vehicleAssignmentRepository = VehicleAssignmentRepositoryI(),
                    companyRepository = CompanyRepositoryI(),
                    vehicleRepository = VehicleRepositoryI(),
                    membershipRepository = harness.membershipRepository,
                    syncService = FakeSyncService(),
                    hub = InMemoryVehicleLocationHub(H3CellIndexer()),
                    tx = harness.tx,
                )
            val service =
                CompanyServiceI(
                    companyRepository = companyRepository,
                    membershipRepository = harness.membershipRepository,
                    companyFavoriteRepository = CompanyFavoriteRepositoryI(),
                    vehicleAssignmentService = vehicleAssignmentService,
                    syncService = FakeSyncService(),
                    tx = harness.tx,
                )

            val results =
                coroutineScope {
                    val first =
                        async(Dispatchers.IO) {
                            service.demoteMember(adminUserId = 1, companyId = company.id, targetUserId = 1)
                        }
                    withTimeout(5_000) { firstLockAcquired.await() }

                    val second =
                        async(Dispatchers.IO) {
                            service.leaveCompany(userId = 2, companyId = company.id)
                        }
                    try {
                        withTimeout(5_000) { secondLockRequested.await() }
                        assertEquals(
                            null,
                            withTimeoutOrNull(250) { secondLockAcquired.await() },
                            "the second demotion must wait for the first transaction's company-row lock",
                        )
                    } finally {
                        releaseFirstLock.complete(Unit)
                    }

                    listOf(first.await(), second.await())
                }

            assertEquals(
                1,
                results.count { it is AppResult.Success },
                "one demotion should succeed",
            )
            assertEquals(
                1,
                results.count { it is AppResult.Error && it.errorType == CompanyError.LAST_ADMIN },
                "the second demotion must observe the first committed demotion",
            )
            assertEquals(1, harness.tx.transactional { harness.membershipRepository.countAdmins(company.id) })
            assertEquals(
                CompanyRole.MEMBER,
                harness.tx.transactional { harness.membershipRepository.find(company.id, 1)?.role },
            )
            assertEquals(
                CompanyRole.ADMIN,
                harness.tx.transactional { harness.membershipRepository.find(company.id, 2)?.role },
            )
        }
    }

    private class CoordinatedCompanyRepository(
        private val delegate: CompanyRepository,
        private val firstLockAcquired: CompletableDeferred<Unit>,
        private val secondLockRequested: CompletableDeferred<Unit>,
        private val secondLockAcquired: CompletableDeferred<Unit>,
        private val releaseFirstLock: CompletableDeferred<Unit>,
    ) : CompanyRepository by delegate {
        private val lockCallCount = AtomicInteger()

        override suspend fun findByIdForUpdate(companyId: Int): Company? {
            val callNumber = lockCallCount.incrementAndGet()
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

                else -> {
                    error("Unexpected company lock call $callNumber")
                }
            }

            return company
        }
    }

    private suspend fun seedCompanyDeletion(harness: CompanyTestHarness): CompanyDeleteFixture {
        val companyToDelete =
            harness.service.createCompany(creatorUserId = 1, name = "Deleted Company").fold(
                onSuccess = { it },
                onError = { error("setup failed: $it") },
            )
        val survivingCompany =
            harness.service.createCompany(creatorUserId = 1, name = "Surviving Company").fold(
                onSuccess = { it },
                onError = { error("setup failed: $it") },
            )
        harness.service.joinCompany(userId = 2, inviteCode = companyToDelete.inviteCode).fold(
            onSuccess = {},
            onError = { error("setup failed: $it") },
        )

        val vehicleRepository = VehicleRepositoryI()
        val assignmentRepository = VehicleAssignmentRepositoryI()
        val favoriteRepository = CompanyFavoriteRepositoryI()
        val savedLocationRepository = SavedLocationRepositoryI()
        val proximityNotificationRepository = ProximityNotificationRepositoryI()
        val locationRepository = VehicleLocationRepositoryI()
        val now = Instant.now()
        val cellIndexer = H3CellIndexer()
        val (vehicleId, assignmentId, savedLocationId) =
            harness.tx.transactional {
                val vehicle =
                    checkNotNull(
                        vehicleRepository
                            .create(
                                companyId = companyToDelete.id,
                                label = "Family Truck",
                                licensePlate = "DELETE-CASCADE",
                            ),
                    )
                val assignment =
                    checkNotNull(
                        assignmentRepository.startAssignment(
                            vehicleId = vehicle.id,
                            vendorUserId = 2,
                            startedAt = now,
                        ),
                    )
                checkNotNull(favoriteRepository.addIfAbsent(userId = 2, companyId = companyToDelete.id))
                checkNotNull(favoriteRepository.addIfAbsent(userId = 2, companyId = survivingCompany.id))
                val savedLocation =
                    savedLocationRepository.create(
                        userId = 2,
                        label = "Personal home",
                        latitude = 47.4979,
                        longitude = 19.0402,
                        radiusKm = 2.5,
                    )
                proximityNotificationRepository.recordEntryAttempted(
                    buyerUserId = 2,
                    savedLocationId = savedLocation.id,
                    vehicleId = vehicle.id,
                    attemptedAt = now,
                )
                VehiclePingsTable.insert {
                    it[VehiclePingsTable.vehicleId] = vehicle.id
                    it[VehiclePingsTable.buyerUserId] = 2
                    it[VehiclePingsTable.sentAt] = now
                }
                locationRepository.insertAll(
                    listOf(
                        VehicleLocationUpdate(
                            vehicleId = vehicle.id,
                            vendorUserId = 2,
                            latitude = 47.4979,
                            longitude = 19.0402,
                            recordedAt = now,
                            cellId = cellIndexer.cellFor(47.4979, 19.0402),
                            assignmentId = assignment.id,
                        ),
                        VehicleLocationUpdate(
                            vehicleId = vehicle.id,
                            vendorUserId = 2,
                            latitude = 47.5,
                            longitude = 19.1,
                            recordedAt = now.plusSeconds(1),
                            cellId = cellIndexer.cellFor(47.5, 19.1),
                        ),
                    ),
                )

                Triple(vehicle.id, assignment.id, savedLocation.id)
            }

        return CompanyDeleteFixture(
            companyId = companyToDelete.id,
            survivingCompanyId = survivingCompany.id,
            vehicleId = vehicleId,
            assignmentId = assignmentId,
            savedLocationId = savedLocationId,
        )
    }

    private suspend fun assertCompanyDeletionEffects(
        harness: CompanyTestHarness,
        fixture: CompanyDeleteFixture,
    ) {
        val vehicleRepository = VehicleRepositoryI()
        val favoriteRepository = CompanyFavoriteRepositoryI()
        val savedLocationRepository = SavedLocationRepositoryI()
        val proximityNotificationRepository = ProximityNotificationRepositoryI()

        assertNull(harness.tx.transactional { CompanyRepositoryI().findById(fixture.companyId) })
        assertNull(harness.tx.transactional { vehicleRepository.findById(fixture.vehicleId) })
        assertEquals(
            0,
            harness.tx.transactional {
                VehicleAssignmentsTable
                    .selectAll()
                    .where { VehicleAssignmentsTable.id eq fixture.assignmentId }
                    .count()
            },
            "the active assignment must be deleted with its company-owned vehicle",
        )
        assertEquals(
            0,
            harness.tx.transactional {
                VehicleLocationsTable
                    .selectAll()
                    .where { VehicleLocationsTable.vehicleId eq fixture.vehicleId }
                    .count()
            },
            "vehicle history linked to the deleted company fleet must not be orphaned",
        )
        assertEquals(
            0,
            harness.tx.transactional {
                VehiclePingsTable
                    .selectAll()
                    .where { VehiclePingsTable.vehicleId eq fixture.vehicleId }
                    .count()
            },
            "pings must cascade with their vehicle even when the buyer account survives",
        )
        assertNull(harness.tx.transactional { harness.membershipRepository.find(fixture.companyId, 1) })
        assertNull(harness.tx.transactional { harness.membershipRepository.find(fixture.companyId, 2) })
        assertNull(
            harness.tx.transactional {
                proximityNotificationRepository.find(
                    buyerUserId = 2,
                    savedLocationId = fixture.savedLocationId,
                    vehicleId = fixture.vehicleId,
                )
            },
            "proximity state must cascade with the vehicle while its user and saved location survive",
        )
        assertEquals(
            listOf(fixture.survivingCompanyId),
            harness.tx.transactional { favoriteRepository.findAllForUser(2) }.map { it.companyId },
            "deleting one company must not remove the same user's favorite for another company",
        )
        val favoriteDeleteEvents =
            harness.syncService.recorded.filter {
                it.entityType == SyncEntityType.COMPANY_FAVORITE && it.operation == SyncOperation.DELETE
            }
        assertEquals(listOf(2 to fixture.companyId), favoriteDeleteEvents.map { it.userId to it.entityId })
        val assignmentDeleteEvents =
            harness.syncService.recorded.filter {
                it.entityType == SyncEntityType.VEHICLE_ASSIGNMENT && it.operation == SyncOperation.DELETE
            }
        assertEquals(listOf(2 to fixture.assignmentId), assignmentDeleteEvents.map { it.userId to it.entityId })
        assertEquals(
            fixture.savedLocationId,
            harness.tx.transactional { savedLocationRepository.findById(fixture.savedLocationId)?.id },
            "a user's saved location is not owned by the company and must survive",
        )
    }
}
