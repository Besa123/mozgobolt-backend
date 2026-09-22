package com.mozgobolt.feature.vehicleAssignment.service

import com.mozgobolt.feature.company.domain.CompanyMembershipRepository
import com.mozgobolt.feature.company.domain.model.CompanyMembership
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.routing.FakeSyncService
import com.mozgobolt.feature.user.service.NoopTransactionalRunner
import com.mozgobolt.feature.vehicle.domain.VehicleRepository
import com.mozgobolt.feature.vehicle.domain.model.Vehicle
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentRepository
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class VehicleAssignmentServiceITest {
    /** A spy, not just a no-op: [markedOffline] lets tests assert *which* vehicle (and how many
     * times) [VehicleAssignmentServiceI] told the hub to clear, since that's the actual behavior
     * under test for unlink/endActiveAssignment — not just "this doesn't crash." */
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

        override fun subscribeAll(): Flow<VehicleLiveEvent> = emptyFlow()

        override fun snapshotNear(
            latitude: Double,
            longitude: Double,
            radiusKm: Double,
        ): List<VehicleLocationUpdate> = emptyList()

        override fun snapshotAll(): List<VehicleLocationUpdate> = emptyList()
    }

    private class FakeVehicleRepository : VehicleRepository {
        private val vehiclesById = mutableMapOf<Int, Vehicle>()

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

        override suspend fun findAllByCompany(companyId: Int) = error("not exercised by this test")

        override suspend fun findByCompanyAndPlate(
            companyId: Int,
            licensePlate: String,
        ) = error("not exercised by this test")

        override suspend fun updateDetails(
            vehicleId: Int,
            licensePlate: String,
            pictureUrl: String?,
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
        ): Vehicle? {
            val existing = vehiclesById[vehicleId] ?: return null
            val updated = existing.copy(pictureUrl = pictureUrl)
            vehiclesById[vehicleId] = updated
            return updated
        }
    }

    private class FakeCompanyMembershipRepository : CompanyMembershipRepository {
        private val memberships = mutableListOf<CompanyMembership>()
        private var nextId = 1

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
        ): CompanyMembership? = memberships.find { it.companyId == companyId && it.userId == userId }

        override suspend fun findAllForCompany(companyId: Int) = error("not exercised by this test")

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
        private var phantomWinnerVendorUserId: Int? = null

        fun loseNextStartToConcurrentWinner(vendorUserId: Int) {
            phantomWinnerVendorUserId = vendorUserId
        }

        override suspend fun findActiveForVendor(vendorUserId: Int): VehicleAssignment? =
            assignments.find { it.vendorUserId == vendorUserId && it.isActive }

        override suspend fun findActiveForVehicle(vehicleId: Int): VehicleAssignment? =
            assignments.find { it.vehicleId == vehicleId && it.isActive }

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

        override suspend fun endAssignment(
            id: Int,
            endedAt: Instant,
        ) {
            val index = assignments.indexOfFirst { it.id == id }
            if (index >= 0) assignments[index] = assignments[index].copy(endedAt = endedAt)
        }
    }

    private class Fixture {
        val vehicleRepository = FakeVehicleRepository()
        val membershipRepository = FakeCompanyMembershipRepository()
        val assignmentRepository = FakeVehicleAssignmentRepository()
        val syncService = FakeSyncService()
        val hub = FakeVehicleLocationHub()
        val service =
            VehicleAssignmentServiceI(
                vehicleAssignmentRepository = assignmentRepository,
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
            fx.service.link(vendorId, 10).fold(onSuccess = { it }, onError = { fail("$it") })

            val second = fx.service.link(vendorId, 20).fold(onSuccess = { it }, onError = { fail("$it") })

            assertEquals(20, second.vehicleId)
            val stillActiveForVehicleTen = fx.assignmentRepository.findActiveForVehicle(10)
            assertNull(stillActiveForVehicleTen, "the old assignment must have been ended, not left dangling active")
            assertEquals(20, fx.service.findActiveForVendor(vendorId)?.vehicleId)
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
    // The internal primitive CompanyServiceI's membership-removal path calls, so that ending a
    // session on removal goes through the same "DB + sync + markOffline" sequence as unlink()/
    // endActiveAssignment(), instead of a caller reaching around this service to do it by hand.

    @Test
    fun `ends the vendor's active assignment when it belongs to the given company`() {
        runBlocking {
            val fx = Fixture()
            val companyId = 1
            val vehicleId = fx.vehicleRepository.seed(id = 1, companyId = companyId).id
            val vendorId = fx.seedVendor(companyId)
            fx.service.link(vendorId, vehicleId).fold(onSuccess = {}, onError = { fail("$it") })

            fx.service.endActiveAssignmentForVendorInCompany(vendorId, companyId)

            assertNull(fx.service.findActiveForVendor(vendorId))
            assertEquals(listOf(vehicleId), fx.hub.markedOffline)
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
}
