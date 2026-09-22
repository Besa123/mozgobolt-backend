package com.mozgobolt.feature.company.service

import com.mozgobolt.feature.company.domain.CompanyMembershipRepository
import com.mozgobolt.feature.company.domain.CompanyRepository
import com.mozgobolt.feature.company.domain.model.Company
import com.mozgobolt.feature.company.domain.model.CompanyMembership
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.sync.routing.FakeSyncService
import com.mozgobolt.feature.user.service.NoopTransactionalRunner
import com.mozgobolt.feature.vehicle.domain.VehicleRepository
import com.mozgobolt.feature.vehicle.domain.model.Vehicle
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentRepository
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import com.mozgobolt.feature.vehicleAssignment.service.VehicleAssignmentServiceI
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLiveEvent
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.Instant

/** Shared across [CompanyServiceCreateJoinTest], [CompanyServiceAdminActionsTest] and
 * [CompanyServiceMembershipRemovalTest] — split into three files purely to keep each test class a
 * manageable size; they all exercise the same [CompanyServiceI]. */
internal class FakeCompanyRepository : CompanyRepository {
    private val companiesById = mutableMapOf<Int, Company>()
    val createCalls = mutableListOf<Company>()
    private var nextId = 1

    override suspend fun create(
        name: String,
        inviteCode: String,
    ): Company {
        val company = Company(id = nextId++, name = name, inviteCode = inviteCode, createdAt = Instant.now())
        companiesById[company.id] = company
        createCalls += company
        return company
    }

    override suspend fun findByInviteCode(inviteCode: String): Company? =
        companiesById.values.find { it.inviteCode == inviteCode }

    override suspend fun findById(companyId: Int): Company? = companiesById[companyId]

    override suspend fun rename(
        companyId: Int,
        newName: String,
    ): Company? {
        val renamed = companiesById[companyId]?.copy(name = newName) ?: return null
        companiesById[companyId] = renamed
        return renamed
    }

    override suspend fun updateInviteCode(
        companyId: Int,
        newInviteCode: String,
    ): Company? {
        val updated = companiesById[companyId]?.copy(inviteCode = newInviteCode) ?: return null
        companiesById[companyId] = updated
        return updated
    }

    override suspend fun delete(companyId: Int): Boolean = companiesById.remove(companyId) != null
}

internal class FakeCompanyMembershipRepository : CompanyMembershipRepository {
    private val memberships = mutableListOf<CompanyMembership>()
    private var nextId = 1
    var rejectNextAdd = false

    override suspend fun addIfAbsent(
        companyId: Int,
        userId: Int,
        role: CompanyRole,
    ): CompanyMembership? {
        if (rejectNextAdd) {
            rejectNextAdd = false
            return null
        }
        if (memberships.any { it.companyId == companyId && it.userId == userId }) return null
        val membership =
            CompanyMembership(
                id = nextId++,
                companyId = companyId,
                userId = userId,
                role = role,
                joinedAt = Instant.now(),
            )
        memberships += membership
        return membership
    }

    override suspend fun find(
        companyId: Int,
        userId: Int,
    ): CompanyMembership? = memberships.find { it.companyId == companyId && it.userId == userId }

    override suspend fun findAllForCompany(companyId: Int): List<CompanyMembership> =
        memberships.filter { it.companyId == companyId }

    override suspend fun countAdmins(companyId: Int): Int =
        memberships.count { it.companyId == companyId && it.role == CompanyRole.ADMIN }

    override suspend fun updateRole(
        companyId: Int,
        userId: Int,
        role: CompanyRole,
    ) {
        val index = memberships.indexOfFirst { it.companyId == companyId && it.userId == userId }
        if (index >= 0) memberships[index] = memberships[index].copy(role = role)
    }

    override suspend fun remove(
        companyId: Int,
        userId: Int,
    ) {
        memberships.removeAll { it.companyId == companyId && it.userId == userId }
    }
}

internal class FakeVehicleRepository : VehicleRepository {
    private val vehiclesById = mutableMapOf<Int, Vehicle>()
    private var nextId = 1

    fun seed(companyId: Int): Vehicle {
        val vehicle =
            Vehicle(
                id = nextId++,
                companyId = companyId,
                label = "v",
                licensePlate = "PLATE-$nextId",
                pictureUrl = null,
                createdAt = Instant.now(),
            )
        vehiclesById[vehicle.id] = vehicle
        return vehicle
    }

    override suspend fun create(
        companyId: Int,
        label: String,
        licensePlate: String,
        pictureUrl: String?,
    ) = error("not exercised by this test")

    override suspend fun findById(vehicleId: Int): Vehicle? = vehiclesById[vehicleId]

    override suspend fun findAllByCompany(companyId: Int) = vehiclesById.values.filter { it.companyId == companyId }

    override suspend fun findByCompanyAndPlate(
        companyId: Int,
        licensePlate: String,
    ) = error("not exercised by this test")

    override suspend fun updateDetails(
        vehicleId: Int,
        licensePlate: String,
        pictureUrl: String?,
    ) = error("not exercised by this test")

    override suspend fun archive(vehicleId: Int) = error("not exercised by this test")

    override suspend fun updatePictureUrl(
        vehicleId: Int,
        pictureUrl: String?,
    ) = error("not exercised by this test")
}

internal class FakeVehicleAssignmentRepository : VehicleAssignmentRepository {
    private val assignments = mutableListOf<VehicleAssignment>()
    private var nextId = 1

    fun seedActive(
        vehicleId: Int,
        vendorUserId: Int,
    ): VehicleAssignment {
        val assignment =
            VehicleAssignment(
                id = nextId++,
                vehicleId = vehicleId,
                vendorUserId = vendorUserId,
                startedAt = Instant.now(),
            )
        assignments += assignment
        return assignment
    }

    override suspend fun findActiveForVendor(vendorUserId: Int): VehicleAssignment? =
        assignments.find { it.vendorUserId == vendorUserId && it.isActive }

    override suspend fun findActiveForVehicle(vehicleId: Int): VehicleAssignment? =
        assignments.find { it.vehicleId == vehicleId && it.isActive }

    override suspend fun startAssignment(
        vehicleId: Int,
        vendorUserId: Int,
        startedAt: Instant,
    ) = error("not exercised by this test")

    override suspend fun endAssignment(
        id: Int,
        endedAt: Instant,
    ) {
        val index = assignments.indexOfFirst { it.id == id }
        if (index >= 0) assignments[index] = assignments[index].copy(endedAt = endedAt)
    }
}

/** Records [markOffline] calls; every other method is unexercised by these tests and throws if
 * called, so an unexpected code path fails loudly instead of silently no-op'ing. */
internal class SpyVehicleLocationHub : VehicleLocationHub {
    val markedOffline = mutableListOf<Int>()

    override fun markOffline(vehicleId: Int) {
        markedOffline += vehicleId
    }

    override fun publish(update: VehicleLocationUpdate) = error("not exercised by this test")

    override fun lastKnownLocation(vehicleId: Int) = error("not exercised by this test")

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
    ) = error("not exercised by this test")

    override fun snapshotAll() = error("not exercised by this test")
}

internal class CompanyServiceFixture {
    val companyRepository = FakeCompanyRepository()
    val membershipRepository = FakeCompanyMembershipRepository()
    val vehicleRepository = FakeVehicleRepository()
    val assignmentRepository = FakeVehicleAssignmentRepository()
    val locationHub = SpyVehicleLocationHub()
    val syncService = FakeSyncService()

    // The real VehicleAssignmentServiceI, not a hand-rolled fake — so
    // endActiveAssignmentForVendorInCompany's actual behavior (including the markOffline call
    // CompanyServiceI used to skip entirely) is genuinely exercised, not re-described.
    private val vehicleAssignmentService =
        VehicleAssignmentServiceI(
            vehicleAssignmentRepository = assignmentRepository,
            vehicleRepository = vehicleRepository,
            membershipRepository = membershipRepository,
            syncService = syncService,
            hub = locationHub,
            tx = NoopTransactionalRunner(),
        )

    val service =
        CompanyServiceI(
            companyRepository = companyRepository,
            membershipRepository = membershipRepository,
            vehicleAssignmentService = vehicleAssignmentService,
            syncService = syncService,
            tx = NoopTransactionalRunner(),
        )
}
