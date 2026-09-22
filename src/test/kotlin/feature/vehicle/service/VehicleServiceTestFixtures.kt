package com.mozgobolt.feature.vehicle.service

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.media.ImageSanitizer
import com.mozgobolt.core.domain.media.ImageStorage
import com.mozgobolt.core.domain.media.SanitizedImage
import com.mozgobolt.core.domain.security.VirusScanResult
import com.mozgobolt.core.domain.security.VirusScanner
import com.mozgobolt.feature.company.domain.CompanyMembershipRepository
import com.mozgobolt.feature.company.domain.model.CompanyMembership
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.sync.routing.FakeSyncService
import com.mozgobolt.feature.user.service.NoopTransactionalRunner
import com.mozgobolt.feature.vehicle.domain.VehicleRepository
import com.mozgobolt.feature.vehicle.domain.model.Vehicle
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignmentError
import java.time.Instant

internal class FakeVehicleRepository : VehicleRepository {
    private val vehiclesById = mutableMapOf<Int, Vehicle>()
    private var nextId = 1

    override suspend fun create(
        companyId: Int,
        label: String,
        licensePlate: String,
        pictureUrl: String?,
    ): Vehicle? {
        if (vehiclesById.values.any { it.companyId == companyId && it.licensePlate == licensePlate }) return null
        val vehicle =
            Vehicle(
                id = nextId++,
                companyId = companyId,
                label = label,
                licensePlate = licensePlate,
                pictureUrl = pictureUrl,
                createdAt = Instant.now(),
            )
        vehiclesById[vehicle.id] = vehicle
        return vehicle
    }

    override suspend fun findById(vehicleId: Int): Vehicle? = vehiclesById[vehicleId]

    override suspend fun findAllByCompany(companyId: Int): List<Vehicle> =
        vehiclesById.values.filter { it.companyId == companyId && it.archivedAt == null }

    override suspend fun findByCompanyAndPlate(
        companyId: Int,
        licensePlate: String,
    ): Vehicle? = vehiclesById.values.find { it.companyId == companyId && it.licensePlate == licensePlate }

    override suspend fun updateDetails(
        vehicleId: Int,
        licensePlate: String,
        pictureUrl: String?,
    ): Vehicle? {
        val existing = vehiclesById[vehicleId] ?: return null
        val updated = existing.copy(licensePlate = licensePlate, pictureUrl = pictureUrl)
        vehiclesById[vehicleId] = updated
        return updated
    }

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

internal class FakeCompanyMembershipRepository : CompanyMembershipRepository {
    private val memberships = mutableListOf<CompanyMembership>()
    private var nextId = 1

    fun seed(
        companyId: Int,
        userId: Int,
        role: CompanyRole,
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

/**
 * A vendor can only ever be force-ended one way (the real service's own admin-permission check,
 * DB end, sync, and `hub.markOffline` are all its concern, not something [VehicleServiceI] should
 * re-implement) — this fake only needs to report success/failure and count calls, so
 * [VehicleServiceI.archiveVehicle]'s own logic (skip if already archived, abort if ending fails
 * for any reason other than "nothing to end") is what's actually under test, not
 * `VehicleAssignmentServiceI` itself (already covered by its own test suite).
 */
internal class FakeVehicleAssignmentService(
    private var endActiveAssignmentResult: AppResult<Unit, VehicleAssignmentError> =
        AppResult.Error(VehicleAssignmentError.NO_ACTIVE_ASSIGNMENT),
) : VehicleAssignmentService {
    var endActiveAssignmentCallCount = 0
        private set

    fun setEndActiveAssignmentResult(result: AppResult<Unit, VehicleAssignmentError>) {
        endActiveAssignmentResult = result
    }

    override suspend fun link(
        vendorUserId: Int,
        vehicleId: Int,
    ) = error("not exercised by this test")

    override suspend fun unlink(
        vendorUserId: Int,
        vehicleId: Int,
    ) = error("not exercised by this test")

    override suspend fun endActiveAssignment(
        adminUserId: Int,
        vehicleId: Int,
    ): AppResult<Unit, VehicleAssignmentError> {
        endActiveAssignmentCallCount++
        return endActiveAssignmentResult
    }

    override suspend fun endActiveAssignmentForVendorInCompany(
        vendorUserId: Int,
        companyId: Int,
    ) = error("not exercised by this test")

    override suspend fun endAllActiveAssignmentsForCompany(companyId: Int) = error("not exercised by this test")

    override suspend fun findActiveForVendor(vendorUserId: Int): VehicleAssignment? =
        error("not exercised by this test")

    override suspend fun findActiveForVehicle(vehicleId: Int): VehicleAssignment? = error("not exercised by this test")
}

/** One fixture class, plain properties, no destructuring — mirrors CompanyServiceTestFixtures'
 * CompanyServiceFixture shape rather than a positional tuple, so adding a new fake later never
 * breaks an existing test's destructuring arity. */
internal data class VehicleServiceFixture(
    val membershipRepository: FakeCompanyMembershipRepository = FakeCompanyMembershipRepository(),
    val vehicleRepository: FakeVehicleRepository = FakeVehicleRepository(),
    val vehicleAssignmentService: FakeVehicleAssignmentService = FakeVehicleAssignmentService(),
    val syncService: FakeSyncService = FakeSyncService(),
    val imageSanitizer: FakeImageSanitizer = FakeImageSanitizer(),
    val virusScanner: FakeVirusScanner = FakeVirusScanner(),
    val imageStorage: FakeImageStorage = FakeImageStorage(),
) {
    val service =
        VehicleServiceI(
            vehicleRepository = vehicleRepository,
            membershipRepository = membershipRepository,
            vehicleAssignmentService = vehicleAssignmentService,
            syncService = syncService,
            imageSanitizer = imageSanitizer,
            virusScanner = virusScanner,
            imageStorage = imageStorage,
            tx = NoopTransactionalRunner(),
        )
}

internal class FakeImageSanitizer(
    private val result: AppResult<SanitizedImage, com.mozgobolt.core.domain.media.ImageSanitizationError> =
        AppResult.Success(SanitizedImage(bytes = byteArrayOf(1), width = 1, height = 1, contentType = "image/jpeg")),
) : ImageSanitizer {
    override suspend fun sanitize(rawBytes: ByteArray) = result
}

internal class FakeVirusScanner(
    private val result: VirusScanResult = VirusScanResult.Clean,
) : VirusScanner {
    override suspend fun scan(bytes: ByteArray) = result
}

internal class FakeImageStorage : ImageStorage {
    val stored = mutableMapOf<String, ByteArray>()

    override suspend fun store(
        key: String,
        bytes: ByteArray,
    ) {
        stored[key] = bytes
    }

    override suspend fun read(key: String): ByteArray? = stored[key]

    override suspend fun deleteBestEffort(keys: List<String>) {
        keys.forEach(stored::remove)
    }
}
