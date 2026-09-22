package com.mozgobolt.feature.vehicle.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.media.ImageSanitizer
import com.mozgobolt.core.domain.media.ImageStorage
import com.mozgobolt.core.domain.security.VirusScanResult
import com.mozgobolt.core.domain.security.VirusScanner
import com.mozgobolt.feature.company.domain.CompanyMembershipRepository
import com.mozgobolt.feature.company.domain.MembershipCheck
import com.mozgobolt.feature.company.domain.requireAdminMembership
import com.mozgobolt.feature.sync.domain.SyncService
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.vehicle.domain.VehicleRepository
import com.mozgobolt.feature.vehicle.domain.VehicleService
import com.mozgobolt.feature.vehicle.domain.model.Vehicle
import com.mozgobolt.feature.vehicle.domain.model.VehicleError
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignmentError

private fun pictureStorageKey(vehicleId: Int) = "vehicles/$vehicleId"

private fun pictureRoute(vehicleId: Int) = "/vehicles/$vehicleId/picture"

class VehicleServiceI(
    private val vehicleRepository: VehicleRepository,
    private val membershipRepository: CompanyMembershipRepository,
    private val vehicleAssignmentService: VehicleAssignmentService,
    private val syncService: SyncService,
    private val imageSanitizer: ImageSanitizer,
    private val virusScanner: VirusScanner,
    private val imageStorage: ImageStorage,
    private val tx: TransactionalRunner,
) : VehicleService {
    @Suppress("ReturnCount")
    override suspend fun createVehicle(
        adminUserId: Int,
        companyId: Int,
        label: String,
        licensePlate: String,
        pictureUrl: String?,
    ): AppResult<Vehicle, VehicleError> {
        val trimmedLabel = label.trim()
        val trimmedPlate = licensePlate.trim()

        return tx.transactional {
            when (membershipRepository.requireAdminMembership(companyId, adminUserId)) {
                MembershipCheck.NotAMember -> return@transactional AppResult.Error(VehicleError.NOT_A_MEMBER)
                MembershipCheck.NotAdmin -> return@transactional AppResult.Error(VehicleError.NOT_ADMIN)
                is MembershipCheck.Admin -> Unit
            }

            val vehicle =
                vehicleRepository.create(
                    companyId = companyId,
                    label = trimmedLabel,
                    licensePlate = trimmedPlate,
                    pictureUrl = pictureUrl,
                ) ?: return@transactional AppResult.Error(VehicleError.LICENSE_PLATE_TAKEN)
            syncService.recordChange(adminUserId, SyncEntityType.VEHICLE, vehicle.id, SyncOperation.UPSERT)

            AppResult.Success(vehicle)
        }
    }

    @Suppress("ReturnCount")
    override suspend fun updateVehicle(
        adminUserId: Int,
        companyId: Int,
        vehicleId: Int,
        licensePlate: String,
        pictureUrl: String?,
    ): AppResult<Vehicle, VehicleError> {
        val trimmedPlate = licensePlate.trim()

        return tx.transactional {
            when (membershipRepository.requireAdminMembership(companyId, adminUserId)) {
                MembershipCheck.NotAMember -> return@transactional AppResult.Error(VehicleError.NOT_A_MEMBER)
                MembershipCheck.NotAdmin -> return@transactional AppResult.Error(VehicleError.NOT_ADMIN)
                is MembershipCheck.Admin -> Unit
            }

            val existing =
                vehicleRepository.findById(vehicleId)
                    ?: return@transactional AppResult.Error(VehicleError.VEHICLE_NOT_FOUND)
            if (existing.companyId != companyId) return@transactional AppResult.Error(VehicleError.VEHICLE_NOT_FOUND)
            // An archived vehicle behaves as if it doesn't exist for normal fleet operations,
            // consistent with it being excluded from both listing endpoints.
            if (existing.archivedAt != null) return@transactional AppResult.Error(VehicleError.VEHICLE_NOT_FOUND)

            // Plain read-then-update, not insertIgnore-backed — this is a single-row UPDATE, not
            // an insert, so there's no ON CONFLICT DO NOTHING equivalent available here. Accepted:
            // this is a rare, low-frequency admin action (renaming a plate), the same risk
            // tolerance already agreed for this project elsewhere (see
            // CompanyServiceI.requireNotLastAdmin's kdoc) — not worth an atomic conditional-UPDATE
            // rewrite for a race this unlikely to ever matter in practice.
            if (trimmedPlate != existing.licensePlate) {
                val plateOwner = vehicleRepository.findByCompanyAndPlate(companyId, trimmedPlate)
                if (plateOwner != null && plateOwner.id != vehicleId) {
                    return@transactional AppResult.Error(VehicleError.LICENSE_PLATE_TAKEN)
                }
            }

            val updated =
                vehicleRepository.updateDetails(vehicleId, trimmedPlate, pictureUrl)
                    ?: return@transactional AppResult.Error(VehicleError.VEHICLE_NOT_FOUND)
            syncService.recordChange(adminUserId, SyncEntityType.VEHICLE, vehicleId, SyncOperation.UPSERT)

            AppResult.Success(updated)
        }
    }

    override suspend fun listCompanyVehicles(
        userId: Int,
        companyId: Int,
    ): AppResult<List<Vehicle>, VehicleError> =
        tx.transactional {
            membershipRepository.find(companyId, userId)
                ?: return@transactional AppResult.Error(VehicleError.NOT_A_MEMBER)

            AppResult.Success(vehicleRepository.findAllByCompany(companyId))
        }

    override suspend fun findVehicle(vehicleId: Int): AppResult<Vehicle, VehicleError> =
        tx.transactional {
            vehicleRepository
                .findById(vehicleId)
                ?.takeIf { it.archivedAt == null }
                ?.let { AppResult.Success(it) }
                ?: AppResult.Error(VehicleError.VEHICLE_NOT_FOUND)
        }

    @Suppress("ReturnCount")
    override suspend fun archiveVehicle(
        adminUserId: Int,
        companyId: Int,
        vehicleId: Int,
    ): AppResult<Unit, VehicleError> =
        tx.transactional {
            when (membershipRepository.requireAdminMembership(companyId, adminUserId)) {
                MembershipCheck.NotAMember -> return@transactional AppResult.Error(VehicleError.NOT_A_MEMBER)
                MembershipCheck.NotAdmin -> return@transactional AppResult.Error(VehicleError.NOT_ADMIN)
                is MembershipCheck.Admin -> Unit
            }

            val existing =
                vehicleRepository.findById(vehicleId)
                    ?: return@transactional AppResult.Error(VehicleError.VEHICLE_NOT_FOUND)
            if (existing.companyId != companyId) return@transactional AppResult.Error(VehicleError.VEHICLE_NOT_FOUND)

            // Already archived: a clean no-op success — nothing left to do, since an archived
            // vehicle can never gain a new active assignment (see VehicleAssignmentError.
            // VEHICLE_ARCHIVED in link()), so there's nothing to force-end a second time either.
            if (existing.archivedAt != null) return@transactional AppResult.Success(Unit)

            // Force-end whatever active session this vehicle has, through the one path allowed to
            // end an assignment (DB end + sync + hub.markOffline) — reuses the already admin-
            // checked endActiveAssignment rather than adding a redundant no-permission-check
            // variant, since the admin check it repeats is harmless (same admin, just validated
            // above). NO_ACTIVE_ASSIGNMENT just means there was nothing to end; anything else here
            // would mean the vehicle or admin's membership changed between the checks above and
            // this call, within the same transaction — vanishingly unlikely, but treated as a
            // reason to abort the archive rather than proceed inconsistently.
            vehicleAssignmentService.endActiveAssignment(adminUserId, vehicleId).fold(
                onSuccess = {},
                onError = { error ->
                    if (error != VehicleAssignmentError.NO_ACTIVE_ASSIGNMENT) {
                        return@transactional AppResult.Error(VehicleError.VEHICLE_NOT_FOUND)
                    }
                },
            )

            vehicleRepository.archive(vehicleId)
            syncService.recordChange(adminUserId, SyncEntityType.VEHICLE, vehicleId, SyncOperation.UPSERT)

            AppResult.Success(Unit)
        }

    @Suppress("ReturnCount")
    override suspend fun uploadVehiclePicture(
        adminUserId: Int,
        companyId: Int,
        vehicleId: Int,
        rawBytes: ByteArray,
    ): AppResult<Vehicle, VehicleError> {
        val precheckError = tx.transactional { checkCanUploadPicture(adminUserId, companyId, vehicleId) }
        precheckError?.let { return AppResult.Error(it) }

        if (virusScanner.scan(rawBytes) != VirusScanResult.Clean) return AppResult.Error(VehicleError.INVALID_IMAGE)

        val sanitized =
            when (val result = imageSanitizer.sanitize(rawBytes)) {
                is AppResult.Error -> return AppResult.Error(VehicleError.INVALID_IMAGE)
                is AppResult.Success -> result.data
            }

        imageStorage.store(pictureStorageKey(vehicleId), sanitized.bytes)

        return tx.transactional {
            vehicleRepository
                .updatePictureUrl(vehicleId, pictureRoute(vehicleId))
                ?.also {
                    syncService.recordChange(
                        adminUserId,
                        SyncEntityType.VEHICLE,
                        vehicleId,
                        SyncOperation.UPSERT,
                    )
                }?.let { AppResult.Success(it) }
                ?: AppResult.Error(VehicleError.VEHICLE_NOT_FOUND)
        }
    }

    override suspend fun getVehiclePicture(vehicleId: Int): AppResult<ByteArray, VehicleError> =
        findVehicle(vehicleId).fold(
            onSuccess = {
                imageStorage.read(pictureStorageKey(vehicleId))?.let { AppResult.Success(it) }
                    ?: AppResult.Error(VehicleError.VEHICLE_NOT_FOUND)
            },
            onError = { AppResult.Error(it) },
        )

    private suspend fun checkCanUploadPicture(
        adminUserId: Int,
        companyId: Int,
        vehicleId: Int,
    ): VehicleError? {
        val membershipError =
            when (membershipRepository.requireAdminMembership(companyId, adminUserId)) {
                MembershipCheck.NotAMember -> VehicleError.NOT_A_MEMBER
                MembershipCheck.NotAdmin -> VehicleError.NOT_ADMIN
                is MembershipCheck.Admin -> null
            }
        membershipError?.let { return it }

        val existing = vehicleRepository.findById(vehicleId) ?: return VehicleError.VEHICLE_NOT_FOUND
        return VehicleError.VEHICLE_NOT_FOUND.takeIf { existing.companyId != companyId || existing.archivedAt != null }
    }
}
