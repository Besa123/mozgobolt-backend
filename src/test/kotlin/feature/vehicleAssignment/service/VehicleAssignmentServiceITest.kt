/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleAssignment.service

import com.mozgobolt.feature.company.domain.CompanyMembershipRepository
import com.mozgobolt.feature.company.domain.CompanyRepository
import com.mozgobolt.feature.company.domain.model.CompanyMembership
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.routing.FakeSyncService
import com.mozgobolt.feature.user.service.NoopTransactionalRunner
import com.mozgobolt.feature.vehicle.domain.VehicleRepository
import com.mozgobolt.feature.vehicle.domain.model.Vehicle
import com.mozgobolt.feature.vehicle.service.FakeVehicleCompanyRepository
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentRepository
import com.mozgobolt.feature.vehicleAssignment.domain.model.ActiveVehicleAssignment
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignmentError
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLiveEvent
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class VehicleAssignmentServiceITest {
    /**
     * A spy that lets tests assert which vehicle the service told the hub to clear.
     * The notification count and vehicle ID are the behavior under test.
     */
    private class FakeVehicleLocationHub : VehicleLocationHub {
        val markedOffline = mutableListOf<Int>()

        override fun publish(update: VehicleLocationUpdate) = error("not exercised by this test")

        override fun markOffline(vehicleId: Int) {
            markedOffline += vehicleId
        }

        override fun lastKnownLocation(vehicleId: Int): VehicleLocationUpdate? = null

        override fun subscribeNear(
            latitude: Double,
            longitude: Double,
            radiusKm: Double,
        ): Flow<VehicleLiveEvent> = emptyFlow()

        override fun snapshotNear(
            latitude: Double,
            longitude: Double,
            radiusKm: Double,
        ): List<VehicleLocationUpdate> = emptyList()
    }

    private class FakeVehicleRepository : VehicleRepository {
        private val vehiclesById = mutableMapOf<Int, Vehicle>()
        var beforeNextLockedRead: ((Int) -> Unit)? = null

        fun seed(
            id: Int,
            companyId: Int,
        ): Vehicle {
            val vehicle =
                Vehicle(
                    id = id,
                    companyId = companyId,
                    label = "v$id",
                    licensePlate = "PLATE-$id",
                    pictureUrl = null,
                    createdAt = Instant.now(),
                )
            vehiclesById[id] = vehicle
            return vehicle
        }

        override suspend fun create(
            companyId: Int,
            label: String,
            licensePlate: String,
            pictureUrl: String?,
        ) = error("not exercised by this test")

        override suspend fun findById(vehicleId: Int): Vehicle? = vehiclesById[vehicleId]

        override suspend fun findByIdForUpdate(vehicleId: Int): Vehicle? {
            beforeNextLockedRead?.let { action ->
                beforeNextLockedRead = null
                action(vehicleId)
            }
            return findById(vehicleId)
        }

        fun remove(vehicleId: Int) {
            vehiclesById.remove(vehicleId)
        }

        fun moveToCompany(
            vehicleId: Int,
            companyId: Int,
        ) {
            val vehicle = checkNotNull(vehiclesById[vehicleId])
            vehiclesById[vehicleId] = vehicle.copy(companyId = companyId)
        }

        override suspend fun findAllByCompany(companyId: Int) = error("not exercised by this test")

        override suspend fun findByCompanyAndPlate(
            companyId: Int,
            licensePlate: String,
        ) = error("not exercised by this test")

        override suspend fun updateDetails(
            vehicleId: Int,
            licensePlate: String,
            pictureUrl: String?,
            pictureStorageKey: String?,
        ) = error("not exercised by this test")

        override suspend fun archive(vehicleId: Int): Boolean {
            val existing = vehiclesById[vehicleId] ?: return false
            if (existing.archivedAt != null) return false
            vehiclesById[vehicleId] = existing.copy(archivedAt = Instant.now())
            return true
        }

        override suspend fun updatePictureUrl(
            vehicleId: Int,
            pictureUrl: String?,
            pictureStorageKey: String?,
        ): Vehicle? {
            val existing = vehiclesById[vehicleId] ?: return null
            val updated = existing.copy(pictureUrl = pictureUrl, pictureStorageKey = pictureStorageKey)
            vehiclesById[vehicleId] = updated
            return updated
        }
    }

    private class FakeCompanyMembershipRepository : CompanyMembershipRepository {
        private val memberships = mutableListOf<CompanyMembership>()
        private val lookupCounts = mutableMapOf<Pair<Int, Int>, Int>()
        private var nextId = 1
        var revokeAfterFirstLookup: Pair<Int, Int>? = null

        fun seed(
            companyId: Int,
            userId: Int,
            role: CompanyRole = CompanyRole.MEMBER,
        ) {
            memberships += CompanyMembership(nextId++, companyId, userId, role, Instant.now())
        }

        override suspend fun addIfAbsent(
            companyId: Int,
            userId: Int,
            role: CompanyRole,
        ) = error("not exercised by this test")

        override suspend fun find(
            companyId: Int,
            userId: Int,
        ): CompanyMembership? {
            val key = companyId to userId
            val lookupCount = lookupCounts.getOrDefault(key, 0) + 1
            lookupCounts[key] = lookupCount
            if (key == revokeAfterFirstLookup && lookupCount > 1) return null

            return memberships.find { it.companyId == companyId && it.userId == userId }
        }

        override suspend fun findAllForCompany(companyId: Int) = error("not exercised by this test")

        override suspend fun findCompanySummariesForUser(userId: Int) = error("not exercised by this test")

        override suspend fun countAdmins(companyId: Int) = error("not exercised by this test")

        override suspend fun updateRole(
            companyId: Int,
            userId: Int,
            role: CompanyRole,
        ) = error("not exercised by this test")

        override suspend fun remove(
            companyId: Int,
            userId: Int,
        ) = error("not exercised by this test")
    }

    /** In-memory but faithful to the real repository's contract: `startAssignment` can be forced
     * to report "lost the race" (see [rejectNextStart]) the same way the real one does when
     * `insertIgnore` inserts zero rows — this is what lets [VehicleAssignmentServiceITest]
     * exercise that path without a real database. [loseNextStartToConcurrentWinner] goes further:
     * it simulates a *genuinely* concurrent request from another vendor actually winning the
     * insert between this call's own read and its insert attempt — something an upfront
     * `findActiveForVehicle` read (before any insert) can never observe by construction. */
    private class FakeVehicleAssignmentRepository : VehicleAssignmentRepository {
        private val assignments = mutableListOf<VehicleAssignment>()
        private var nextId = 1
        var rejectNextStart = false
        var rejectNextEnd = false
        private var phantomWinnerVendorUserId: Int? = null

        fun loseNextStartToConcurrentWinner(vendorUserId: Int) {
            phantomWinnerVendorUserId = vendorUserId
        }

        override suspend fun findActiveForVendor(vendorUserId: Int): VehicleAssignment? =
            assignments.find { it.vendorUserId == vendorUserId && it.isActive }

        override suspend fun findActiveDetailsForVendor(vendorUserId: Int): ActiveVehicleAssignment? =
            error("not exercised by this test")

        override suspend fun findActiveForVehicle(vehicleId: Int): VehicleAssignment? =
            assignments.find { it.vehicleId == vehicleId && it.isActive }

        override suspend fun findAllForCompany(companyId: Int): List<VehicleAssignment> = assignments.toList()

        override suspend fun startAssignment(
            vehicleId: Int,
            vendorUserId: Int,
            startedAt: Instant,
        ): VehicleAssignment? {
            phantomWinnerVendorUserId?.let { winnerId ->
                phantomWinnerVendorUserId = null
                assignments +=
                    VehicleAssignment(
                        id = nextId++,
                        vehicleId = vehicleId,
                        vendorUserId = winnerId,
                        startedAt = startedAt,
                    )
                return null
            }
            if (rejectNextStart) {
                rejectNextStart = false
                return null
            }
            val assignment =
                VehicleAssignment(
                    id = nextId++,
                    vehicleId = vehicleId,
                    vendorUserId = vendorUserId,
                    startedAt = startedAt,
                )
            assignments += assignment
            return assignment
        }

        override suspend fun startAssignment(
            vehicleId: Int,
            vendorUserId: Int,
            startedAt: Instant,
            locationSharingConsentedAt: Instant?,
        ): VehicleAssignment? {
            val assignment =
                startAssignment(
                    vehicleId = vehicleId,
                    vendorUserId = vendorUserId,
                    startedAt = startedAt,
                ) ?: return null
            val withConsent = assignment.copy(locationSharingConsentedAt = locationSharingConsentedAt)
            val index = assignments.indexOfFirst { it.id == assignment.id }
            assignments[index] = withConsent
            return withConsent
        }

        override suspend fun endAssignment(
            id: Int,
            endedAt: Instant,
        ): Boolean {
            if (rejectNextEnd) {
                rejectNextEnd = false
                return false
            }
            val index = assignments.indexOfFirst { it.id == id }
            if (index < 0 || !assignments[index].isActive) return false
            assignments[index] = assignments[index].copy(endedAt = endedAt)
            return true
        }
    }

    private class FakeAssignmentCompanyRepository(
        private val delegate: CompanyRepository = FakeVehicleCompanyRepository(),
    ) : CompanyRepository by delegate {
        var missingCompanyOnLock: Int? = null

        override suspend fun findByIdForUpdate(companyId: Int) =
            if (companyId == missingCompanyOnLock) {
                null
            } else {
                delegate.findByIdForUpdate(companyId)
            }
    }

    private class Fixture {
        val vehicleRepository = FakeVehicleRepository()
        val membershipRepository = FakeCompanyMembershipRepository()
        val companyRepository = FakeAssignmentCompanyRepository()
        val assignmentRepository = FakeVehicleAssignmentRepository()
        val syncService = FakeSyncService()
        val hub = FakeVehicleLocationHub()
        val service =
            VehicleAssignmentServiceI(
                vehicleAssignmentRepository = assignmentRepository,
                companyRepository = companyRepository,
                vehicleRepository = vehicleRepository,
                membershipRepository = membershipRepository,
                syncService = syncService,
                hub = hub,
                tx = NoopTransactionalRunner(),
            )
        private var nextUserId = 1000

        fun seedVendor(companyId: Int?): Int {
            val userId = nextUserId++
            if (companyId != null) membershipRepository.seed(companyId, userId)
            return userId
        }
    }

    // --- link() ---

    @Test
    fun `linking to an unassigned vehicle in the vendor's own company succeeds`() {
        runBlocking {
            val fx = Fixture()
            val vendorId = fx.seedVendor(companyId = 1)
            fx.vehicleRepository.seed(id = 10, companyId = 1)

            val result = fx.service.link(vendorUserId = vendorId, vehicleId = 10)

            val assignment = result.fold(onSuccess = { it }, onError = { fail("expected success but got $it") })
            assertEquals(10, assignment.vehicleId)
            assertTrue(assignment.isActive)
            assertTrue(fx.syncService.recorded.any { it.entityType == SyncEntityType.VEHICLE_ASSIGNMENT })
        }
    }

    @Test
    fun `linking with customer sharing requested persists the consent time`() {
        runBlocking {
            val fx = Fixture()
            val vendorId = fx.seedVendor(companyId = 1)
            fx.vehicleRepository.seed(id = 10, companyId = 1)

            val result = fx.service.link(vendorId, 10, shareLocationWithCustomers = true)

            val assignment = result.fold(onSuccess = { it }, onError = { fail("expected success but got $it") })
            assertNotNull(assignment.locationSharingConsentedAt)
            assertTrue(assignment.sharesLocationWithCustomers)
        }
    }

    @Test
    fun `linking to a vehicle that doesn't exist is rejected`() {
        runBlocking {
            val fx = Fixture()
            val vendorId = fx.seedVendor(companyId = 1)

            val result = fx.service.link(vendorUserId = vendorId, vehicleId = 999)

            result.fold(onSuccess = { fail("expected VEHICLE_NOT_FOUND but got success") }, onError = {
                assertEquals(VehicleAssignmentError.VEHICLE_NOT_FOUND, it)
            })
        }
    }

    @Test
    fun `linking to an archived vehicle is rejected`() {
        runBlocking {
            val fx = Fixture()
            val vendorId = fx.seedVendor(companyId = 1)
            fx.vehicleRepository.seed(id = 10, companyId = 1)
            fx.vehicleRepository.archive(10)

            val result = fx.service.link(vendorUserId = vendorId, vehicleId = 10)

            result.fold(onSuccess = { fail("expected VEHICLE_ARCHIVED but got success") }, onError = {
                assertEquals(VehicleAssignmentError.VEHICLE_ARCHIVED, it)
            })
        }
    }

    @Test
    fun `linking to another company's vehicle is forbidden`() {
        runBlocking {
            val fx = Fixture()
            val vendorId = fx.seedVendor(companyId = 1)
            fx.vehicleRepository.seed(id = 10, companyId = 2)

            val result = fx.service.link(vendorUserId = vendorId, vehicleId = 10)

            result.fold(onSuccess = { fail("expected FORBIDDEN but got success") }, onError = {
                assertEquals(VehicleAssignmentError.FORBIDDEN, it)
            })
        }
    }

    @Test
    fun `linking is rejected if the company disappears after the initial vehicle read`() {
        runBlocking {
            val fx = Fixture()
            val vendorId = fx.seedVendor(companyId = 1)
            fx.vehicleRepository.seed(id = 10, companyId = 1)
            fx.companyRepository.missingCompanyOnLock = 1

            val result = fx.service.link(vendorId, 10)

            result.fold(onSuccess = { fail("expected VEHICLE_NOT_FOUND but got success") }, onError = {
                assertEquals(VehicleAssignmentError.VEHICLE_NOT_FOUND, it)
            })
            assertNull(fx.assignmentRepository.findActiveForVendor(vendorId))
        }
    }

    @Test
    fun `linking is rejected if vehicle ownership changes before the locked reread`() {
        runBlocking {
            val fx = Fixture()
            val vendorId = fx.seedVendor(companyId = 1)
            fx.vehicleRepository.seed(id = 10, companyId = 1)
            fx.vehicleRepository.beforeNextLockedRead = { vehicleId ->
                fx.vehicleRepository.moveToCompany(vehicleId, companyId = 2)
            }

            val result = fx.service.link(vendorId, 10)

            result.fold(onSuccess = { fail("expected VEHICLE_NOT_FOUND but got success") }, onError = {
                assertEquals(VehicleAssignmentError.VEHICLE_NOT_FOUND, it)
            })
            assertNull(fx.assignmentRepository.findActiveForVendor(vendorId))
        }
    }

    @Test
    fun `linking is rejected if membership is revoked before the locked authorization recheck`() {
        runBlocking {
            val fx = Fixture()
            val vendorId = fx.seedVendor(companyId = 1)
            fx.vehicleRepository.seed(id = 10, companyId = 1)
            fx.membershipRepository.revokeAfterFirstLookup = 1 to vendorId

            val result = fx.service.link(vendorId, 10)

            result.fold(onSuccess = { fail("expected FORBIDDEN but got success") }, onError = {
                assertEquals(VehicleAssignmentError.FORBIDDEN, it)
            })
            assertNull(fx.assignmentRepository.findActiveForVendor(vendorId))
        }
    }

    @Test
    fun `a vendor with no company membership at all is forbidden from linking to any vehicle`() {
        runBlocking {
            val fx = Fixture()
            val vendorId = fx.seedVendor(companyId = null)
            fx.vehicleRepository.seed(id = 10, companyId = 1)

            val result = fx.service.link(vendorUserId = vendorId, vehicleId = 10)

            result.fold(onSuccess = { fail("expected FORBIDDEN but got success") }, onError = {
                assertEquals(VehicleAssignmentError.FORBIDDEN, it)
            })
        }
    }

    @Test
    fun `a plain member, not just an admin, can link to their company's vehicle`() {
        runBlocking {
            val fx = Fixture()
            val vendorId = fx.seedVendor(companyId = null)
            fx.membershipRepository.seed(companyId = 1, userId = vendorId, role = CompanyRole.MEMBER)
            fx.vehicleRepository.seed(id = 10, companyId = 1)

            val result = fx.service.link(vendorUserId = vendorId, vehicleId = 10)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
        }
    }

    @Test
    fun `linking to a vehicle already actively assigned to a different vendor is rejected`() {
        runBlocking {
            val fx = Fixture()
            val firstVendor = fx.seedVendor(companyId = 1)
            val secondVendor = fx.seedVendor(companyId = 1)
            fx.vehicleRepository.seed(id = 10, companyId = 1)
            fx.service.link(firstVendor, 10).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.service.link(vendorUserId = secondVendor, vehicleId = 10)

            result.fold(onSuccess = { fail("expected VEHICLE_ALREADY_ASSIGNED but got success") }, onError = {
                assertEquals(VehicleAssignmentError.VEHICLE_ALREADY_ASSIGNED, it)
            })
        }
    }

    @Test
    fun `linking to a vehicle the vendor is already linked to is a no-op that returns the existing assignment`() {
        runBlocking {
            val fx = Fixture()
            val vendorId = fx.seedVendor(companyId = 1)
            fx.vehicleRepository.seed(id = 10, companyId = 1)
            val first = fx.service.link(vendorId, 10).fold(onSuccess = { it }, onError = { fail("$it") })

            val result = fx.service.link(vendorUserId = vendorId, vehicleId = 10)

            val second = result.fold(onSuccess = { it }, onError = { fail("expected success but got $it") })
            assertEquals(first.id, second.id, "relinking to the same vehicle must not create a second assignment row")
        }
    }

    @Test
    fun `linking to a new vehicle ends the vendor's previous active assignment`() {
        runBlocking {
            val fx = Fixture()
            val vendorId = fx.seedVendor(companyId = 1)
            fx.vehicleRepository.seed(id = 10, companyId = 1)
            fx.vehicleRepository.seed(id = 20, companyId = 1)
            val first = fx.service.link(vendorId, 10).fold(onSuccess = { it }, onError = { fail("$it") })

            val second = fx.service.link(vendorId, 20).fold(onSuccess = { it }, onError = { fail("$it") })

            assertEquals(20, second.vehicleId)
            val stillActiveForVehicleTen = fx.assignmentRepository.findActiveForVehicle(10)
            assertNull(stillActiveForVehicleTen, "the old assignment must have been ended, not left dangling active")
            assertEquals(20, fx.service.findActiveForVendor(vendorId)?.vehicleId)
            assertEquals(
                2,
                fx.syncService.recorded.count {
                    it.entityType == SyncEntityType.VEHICLE_ASSIGNMENT && it.entityId == first.id
                },
                "sync must include the old assignment's transition to ended",
            )
            assertEquals(
                1,
                fx.syncService.recorded.count {
                    it.entityType == SyncEntityType.VEHICLE_ASSIGNMENT && it.entityId == second.id
                },
                "sync must include the new active assignment",
            )
            assertEquals(listOf(10), fx.hub.markedOffline, "the previous vehicle must leave the live map")
        }
    }

    @Test
    fun `REGRESSION losing the DB-level race is reported as VEHICLE_ALREADY_ASSIGNED, not thrown`() {
        runBlocking {
            val fx = Fixture()
            val vendorId = fx.seedVendor(companyId = 1)
            fx.vehicleRepository.seed(id = 10, companyId = 1)
            fx.assignmentRepository.rejectNextStart = true

            val result = fx.service.link(vendorUserId = vendorId, vehicleId = 10)

            result.fold(onSuccess = { fail("expected VEHICLE_ALREADY_ASSIGNED but got success") }, onError = {
                assertEquals(VehicleAssignmentError.VEHICLE_ALREADY_ASSIGNED, it)
            })
            assertTrue(
                fx.syncService.recorded.isEmpty(),
                "no sync event for an assignment that was never actually created",
            )
        }
    }

    @Test
    fun `REGRESSION losing the race to a concurrent request from the SAME vendor is still a success`() {
        runBlocking {
            val fx = Fixture()
            val vendorId = fx.seedVendor(companyId = 1)
            fx.vehicleRepository.seed(id = 10, companyId = 1)
            fx.assignmentRepository.loseNextStartToConcurrentWinner(vendorId)

            val result = fx.service.link(vendorUserId = vendorId, vehicleId = 10)

            val assignment = result.fold(onSuccess = { it }, onError = { fail("expected success but got $it") })
            assertEquals(vendorId, assignment.vendorUserId)
            assertTrue(
                fx.syncService.recorded.isEmpty(),
                "this call created nothing itself — the concurrent winner already recorded its own sync event",
            )
        }
    }

    @Test
    fun `same-vendor race while switching vehicles still ends and syncs the previous assignment`() {
        runBlocking {
            val fx = Fixture()
            val vendorId = fx.seedVendor(companyId = 1)
            fx.vehicleRepository.seed(id = 10, companyId = 1)
            fx.vehicleRepository.seed(id = 20, companyId = 1)
            val previous =
                fx.service.link(vendorId, 10).fold(onSuccess = { it }, onError = { fail("setup failed: $it") })
            fx.assignmentRepository.loseNextStartToConcurrentWinner(vendorId)

            val result = fx.service.link(vendorId, 20)

            val winner = result.fold(onSuccess = { it }, onError = { fail("expected success but got $it") })
            assertEquals(20, winner.vehicleId)
            assertNull(fx.assignmentRepository.findActiveForVehicle(10))
            assertEquals(2, fx.syncService.recorded.count { it.entityId == previous.id })
            assertEquals(listOf(10), fx.hub.markedOffline)
        }
    }

    @Test
    fun `losing the race to a concurrent request from a DIFFERENT vendor is correctly rejected`() {
        runBlocking {
            val fx = Fixture()
            val vendorId = fx.seedVendor(companyId = 1)
            val otherVendorId = fx.seedVendor(companyId = 1)
            fx.vehicleRepository.seed(id = 10, companyId = 1)
            fx.assignmentRepository.loseNextStartToConcurrentWinner(otherVendorId)

            val result = fx.service.link(vendorUserId = vendorId, vehicleId = 10)

            result.fold(onSuccess = { fail("expected VEHICLE_ALREADY_ASSIGNED but got success") }, onError = {
                assertEquals(VehicleAssignmentError.VEHICLE_ALREADY_ASSIGNED, it)
            })
            assertTrue(
                fx.hub.markedOffline.isEmpty(),
                "the failed switch must not clear the previous vehicle from the live map",
            )
        }
    }

    @Test
    fun `REGRESSION losing the race for a different vehicle still fails cleanly with a prior session active`() {
        // This only proves link() converts the internal rollback signal (LostAssignmentRaceException)
        // back into a normal AppResult.Error rather than letting it escape uncaught, even when a
        // prior active assignment existed and had to be ended first. It does NOT prove that prior
        // assignment's end is actually rolled back — NoopTransactionalRunner has no real
        // transactional semantics to roll back. See VehicleAssignmentServiceRealDatabaseTest's
        // "never loses your own current session" test for the one that proves the actual rollback
        // against a real Postgres transaction.
        runBlocking {
            val fx = Fixture()
            val vendorId = fx.seedVendor(companyId = 1)
            val otherVendorId = fx.seedVendor(companyId = 1)
            fx.vehicleRepository.seed(id = 10, companyId = 1)
            fx.vehicleRepository.seed(id = 20, companyId = 1)
            fx.service.link(vendorId, 10).fold(onSuccess = { it }, onError = { fail("setup failed: $it") })

            fx.assignmentRepository.loseNextStartToConcurrentWinner(otherVendorId)
            val result = fx.service.link(vendorUserId = vendorId, vehicleId = 20)

            result.fold(onSuccess = { fail("expected VEHICLE_ALREADY_ASSIGNED but got success") }, onError = {
                assertEquals(VehicleAssignmentError.VEHICLE_ALREADY_ASSIGNED, it)
            })
            assertTrue(
                fx.hub.markedOffline.isEmpty(),
                "a rolled-back failed vehicle switch must not clear the previous vehicle from the live map",
            )
        }
    }

    // --- unlink() ---

    @Test
    fun `unlinking an active assignment succeeds`() {
        runBlocking {
            val fx = Fixture()
            val vendorId = fx.seedVendor(companyId = 1)
            fx.vehicleRepository.seed(id = 10, companyId = 1)
            fx.service.link(vendorId, 10).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.service.unlink(vendorUserId = vendorId, vehicleId = 10)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertNull(fx.service.findActiveForVendor(vendorId))
            assertEquals(listOf(10), fx.hub.markedOffline, "hub must learn vehicle 10 went offline")
        }
    }

    @Test
    fun `unlinking with no active assignment at all is rejected`() {
        runBlocking {
            val fx = Fixture()
            val vendorId = fx.seedVendor(companyId = 1)

            val result = fx.service.unlink(vendorUserId = vendorId, vehicleId = 10)

            result.fold(onSuccess = { fail("expected NOT_LINKED but got success") }, onError = {
                assertEquals(VehicleAssignmentError.NOT_LINKED, it)
            })
            assertTrue(fx.hub.markedOffline.isEmpty(), "nothing to signal — no assignment was actually ended")
        }
    }

    @Test
    fun `unlinking the wrong vehicle while actively linked to a different one is rejected`() {
        runBlocking {
            val fx = Fixture()
            val vendorId = fx.seedVendor(companyId = 1)
            fx.vehicleRepository.seed(id = 10, companyId = 1)
            fx.vehicleRepository.seed(id = 20, companyId = 1)
            fx.service.link(vendorId, 10).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.service.unlink(vendorUserId = vendorId, vehicleId = 20)

            result.fold(onSuccess = { fail("expected NOT_LINKED but got success") }, onError = {
                assertEquals(VehicleAssignmentError.NOT_LINKED, it)
            })
            // The real (correct) assignment to vehicle 10 must be untouched by the wrong-vehicle
            // unlink attempt.
            assertEquals(10, fx.service.findActiveForVendor(vendorId)?.vehicleId)
            assertTrue(fx.hub.markedOffline.isEmpty(), "nothing to signal — no assignment was actually ended")
        }
    }

    // --- endActiveAssignment() ---

    @Test
    fun `an admin can force-end another vendor's active session on the company's vehicle`() {
        runBlocking {
            val fx = Fixture()
            val adminId = fx.seedVendor(companyId = 1, role = CompanyRole.ADMIN)
            val vendorId = fx.seedVendor(companyId = 1)
            fx.vehicleRepository.seed(id = 10, companyId = 1)
            fx.service.link(vendorId, 10).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.service.endActiveAssignment(adminUserId = adminId, vehicleId = 10)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertNull(fx.service.findActiveForVendor(vendorId))
            assertEquals(listOf(10), fx.hub.markedOffline, "hub must learn vehicle 10 went offline")
        }
    }

    @Test
    fun `a plain member cannot force-end anyone's active session`() {
        runBlocking {
            val fx = Fixture()
            val otherMemberId = fx.seedVendor(companyId = 1)
            val vendorId = fx.seedVendor(companyId = 1)
            fx.vehicleRepository.seed(id = 10, companyId = 1)
            fx.service.link(vendorId, 10).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.service.endActiveAssignment(adminUserId = otherMemberId, vehicleId = 10)

            result.fold(onSuccess = { fail("expected NOT_ADMIN but got success") }, onError = {
                assertEquals(VehicleAssignmentError.NOT_ADMIN, it)
            })
            assertEquals(10, fx.service.findActiveForVendor(vendorId)?.vehicleId)
            assertTrue(fx.hub.markedOffline.isEmpty(), "nothing to signal — no assignment was actually ended")
        }
    }

    @Test
    fun `force-ending a vehicle with no active assignment is rejected`() {
        runBlocking {
            val fx = Fixture()
            val adminId = fx.seedVendor(companyId = 1, role = CompanyRole.ADMIN)
            fx.vehicleRepository.seed(id = 10, companyId = 1)

            val result = fx.service.endActiveAssignment(adminUserId = adminId, vehicleId = 10)

            result.fold(onSuccess = { fail("expected NO_ACTIVE_ASSIGNMENT but got success") }, onError = {
                assertEquals(VehicleAssignmentError.NO_ACTIVE_ASSIGNMENT, it)
            })
            assertTrue(fx.hub.markedOffline.isEmpty(), "nothing to signal — no assignment was actually ended")
        }
    }

    @Test
    fun `force-ending an unknown vehicle is rejected`() {
        runBlocking {
            val fx = Fixture()
            val adminId = fx.seedVendor(companyId = 1, role = CompanyRole.ADMIN)

            val result = fx.service.endActiveAssignment(adminUserId = adminId, vehicleId = 999)

            result.fold(onSuccess = { fail("expected VEHICLE_NOT_FOUND but got success") }, onError = {
                assertEquals(VehicleAssignmentError.VEHICLE_NOT_FOUND, it)
            })
            assertTrue(fx.hub.markedOffline.isEmpty(), "nothing to signal — no assignment was actually ended")
        }
    }

    @Test
    fun `force-ending by an admin of a DIFFERENT company is rejected`() {
        runBlocking {
            val fx = Fixture()
            val otherCompanyAdminId = fx.seedVendor(companyId = 2, role = CompanyRole.ADMIN)
            val vendorId = fx.seedVendor(companyId = 1)
            fx.vehicleRepository.seed(id = 10, companyId = 1)
            fx.service.link(vendorId, 10).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.service.endActiveAssignment(adminUserId = otherCompanyAdminId, vehicleId = 10)

            result.fold(onSuccess = { fail("expected NOT_ADMIN but got success") }, onError = {
                assertEquals(VehicleAssignmentError.NOT_ADMIN, it)
            })
            assertTrue(fx.hub.markedOffline.isEmpty(), "nothing to signal — no assignment was actually ended")
        }
    }

    // --- findActiveForVendor() ---

    @Test
    fun `findActiveForVendor is null for a vendor who has never linked to anything`() {
        runBlocking {
            val fx = Fixture()
            val vendorId = fx.seedVendor(companyId = 1)

            assertNull(fx.service.findActiveForVendor(vendorId))
        }
    }

    private fun Fixture.seedVendor(
        companyId: Int,
        role: CompanyRole,
    ): Int {
        val vendorId = seedVendor(companyId = null)
        membershipRepository.seed(companyId, vendorId, role)
        return vendorId
    }

    // --- endActiveAssignmentForVendorInCompany() ---
    // Internal callers own the corresponding sync event and post-commit live-map notification so
    // they can batch both with other effects in the transaction that owns the mutation.

    @Test
    fun `ends the vendor's active assignment when it belongs to the given company`() {
        runBlocking {
            val fx = Fixture()
            val companyId = 1
            val vehicleId = fx.vehicleRepository.seed(id = 1, companyId = companyId).id
            val vendorId = fx.seedVendor(companyId)
            fx.service.link(vendorId, vehicleId).fold(onSuccess = {}, onError = { fail("$it") })

            val endedAssignment = fx.service.endActiveAssignmentForVendorInCompany(vendorId, companyId)

            assertEquals(vehicleId, endedAssignment?.vehicleId)
            assertEquals(vendorId, endedAssignment?.vendorUserId)
            assertNull(fx.service.findActiveForVendor(vendorId))
            assertTrue(
                fx.hub.markedOffline.isEmpty(),
                "the internal end primitive must leave live notification to its post-commit caller",
            )
            val ended = requireNotNull(endedAssignment)
            fx.service.notifyEndedAssignments(listOf(ended, ended))
            assertEquals(listOf(vehicleId), fx.hub.markedOffline)
            assertEquals(
                1,
                fx.syncService.recorded.count { it.entityType == SyncEntityType.VEHICLE_ASSIGNMENT },
                "only link() should record an event; the caller batches the end event",
            )
        }
    }

    @Test
    fun `does nothing when the vendor's active assignment belongs to a different company`() {
        runBlocking {
            val fx = Fixture()
            val actualCompanyId = 1
            val vehicleId = fx.vehicleRepository.seed(id = 1, companyId = actualCompanyId).id
            val vendorId = fx.seedVendor(actualCompanyId)
            fx.service.link(vendorId, vehicleId).fold(onSuccess = {}, onError = { fail("$it") })

            fx.service.endActiveAssignmentForVendorInCompany(vendorId, companyId = 999)

            assertEquals(vehicleId, fx.service.findActiveForVendor(vendorId)?.vehicleId)
            assertTrue(fx.hub.markedOffline.isEmpty())
        }
    }

    @Test
    fun `does nothing when the vendor has no active assignment at all`() {
        runBlocking {
            val fx = Fixture()
            val vendorId = fx.seedVendor(companyId = 1)

            fx.service.endActiveAssignmentForVendorInCompany(vendorId, companyId = 1)

            assertTrue(fx.hub.markedOffline.isEmpty())
        }
    }

    // --- endAllActiveAssignmentsForCompany() ---

    @Test
    fun `ends all active company assignments in vehicle order without emitting side effects`() {
        runBlocking {
            val fx = Fixture()
            val vehicleTwenty = fx.vehicleRepository.seed(id = 20, companyId = 1)
            val vehicleTen = fx.vehicleRepository.seed(id = 10, companyId = 1)
            val firstVendor = fx.seedVendor(companyId = 1)
            val secondVendor = fx.seedVendor(companyId = 1)
            fx.service.link(firstVendor, vehicleTwenty.id).fold(onSuccess = {}, onError = { fail("$it") })
            fx.service.link(secondVendor, vehicleTen.id).fold(onSuccess = {}, onError = { fail("$it") })
            val syncCountBeforeEnd = fx.syncService.recorded.size

            val ended = fx.service.endAllActiveAssignmentsForCompany(companyId = 1)

            assertEquals(listOf(10, 20), ended.map(VehicleAssignment::vehicleId))
            assertNull(fx.assignmentRepository.findActiveForVehicle(vehicleTen.id))
            assertNull(fx.assignmentRepository.findActiveForVehicle(vehicleTwenty.id))
            assertEquals(syncCountBeforeEnd, fx.syncService.recorded.size)
            assertTrue(fx.hub.markedOffline.isEmpty())
        }
    }

    @Test
    fun `endAllActiveAssignmentsForCompany returns empty when the company is missing`() {
        runBlocking {
            val fx = Fixture()
            val vehicle = fx.vehicleRepository.seed(id = 10, companyId = 1)
            val vendorId = fx.seedVendor(companyId = 1)
            fx.service.link(vendorId, vehicle.id).fold(onSuccess = {}, onError = { fail("$it") })
            fx.companyRepository.missingCompanyOnLock = 1

            val ended = fx.service.endAllActiveAssignmentsForCompany(companyId = 1)

            assertTrue(ended.isEmpty())
            assertEquals(vehicle.id, fx.assignmentRepository.findActiveForVendor(vendorId)?.vehicleId)
        }
    }

    @Test
    fun `endActiveAssignmentForVendor ends and syncs the current assignment without notifying the hub`() {
        runBlocking {
            val fx = Fixture()
            val vehicle = fx.vehicleRepository.seed(id = 10, companyId = 1)
            val vendorId = fx.seedVendor(companyId = 1)
            val assignment =
                fx.service.link(vendorId, vehicle.id).fold(onSuccess = { it }, onError = { fail("$it") })
            val syncCountBeforeEnd = fx.syncService.recorded.size

            val ended = fx.service.endActiveAssignmentForVendor(vendorId)

            assertEquals(assignment.id, ended?.id)
            assertNotNull(ended?.endedAt)
            assertNull(fx.assignmentRepository.findActiveForVendor(vendorId))
            assertEquals(syncCountBeforeEnd + 1, fx.syncService.recorded.size)
            assertTrue(fx.hub.markedOffline.isEmpty())
        }
    }

    @Test
    fun `endActiveAssignmentForVendor returns null when another operation already ended the row`() {
        runBlocking {
            val fx = Fixture()
            val vehicle = fx.vehicleRepository.seed(id = 10, companyId = 1)
            val vendorId = fx.seedVendor(companyId = 1)
            fx.service.link(vendorId, vehicle.id).fold(onSuccess = {}, onError = { fail("$it") })
            val syncCountBeforeEnd = fx.syncService.recorded.size
            fx.assignmentRepository.rejectNextEnd = true

            val ended = fx.service.endActiveAssignmentForVendor(vendorId)

            assertNull(ended)
            assertEquals(vehicle.id, fx.assignmentRepository.findActiveForVendor(vendorId)?.vehicleId)
            assertEquals(syncCountBeforeEnd, fx.syncService.recorded.size)
        }
    }
}
