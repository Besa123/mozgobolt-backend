/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicle.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.media.ImageSanitizer
import com.mozgobolt.core.domain.media.ImageStorage
import com.mozgobolt.core.domain.security.VirusScanResult
import com.mozgobolt.core.domain.security.VirusScanner
import com.mozgobolt.core.utility.functions.runSuspendCatching
import com.mozgobolt.feature.company.domain.CompanyMembershipRepository
import com.mozgobolt.feature.company.domain.CompanyRepository
import com.mozgobolt.feature.company.domain.MembershipCheck
import com.mozgobolt.feature.company.domain.requireAdminMembership
import com.mozgobolt.feature.sync.domain.SyncService
import com.mozgobolt.feature.sync.domain.model.SyncChange
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.vehicle.domain.VehicleRepository
import com.mozgobolt.feature.vehicle.domain.VehicleService
import com.mozgobolt.feature.vehicle.domain.model.Vehicle
import com.mozgobolt.feature.vehicle.domain.model.VehicleError
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID

private val logger = KotlinLogging.logger {}

private fun pictureStorageKey(
    vehicleId: Int,
    version: UUID,
) = "vehicles/$vehicleId/$version"

private fun pictureRoute(vehicleId: Int) = "/vehicles/$vehicleId/picture"

private data class PictureUpdate(
    val url: String?,
    val storageKey: String?,
    val storageKeyToDelete: String?,
)

private fun Vehicle.pictureUpdate(
    pictureUrl: String?,
    clearPictureUrl: Boolean,
): PictureUpdate {
    val hasChanged = clearPictureUrl || (pictureUrl != null && pictureUrl != this.pictureUrl)
    return PictureUpdate(
        url =
            when {
                clearPictureUrl -> null
                pictureUrl != null -> pictureUrl
                else -> this.pictureUrl
            },
        storageKey = pictureStorageKey.takeUnless { hasChanged },
        storageKeyToDelete = pictureStorageKey.takeIf { hasChanged },
    )
}

@Suppress("LongParameterList")
class VehicleServiceI(
    private val companyRepository: CompanyRepository,
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
            requireAdminAccess(adminUserId, companyId)?.let {
                return@transactional AppResult.Error(it)
            }

            val vehicle =
                vehicleRepository.create(
                    companyId = companyId,
                    label = trimmedLabel,
                    licensePlate = trimmedPlate,
                    pictureUrl = pictureUrl,
                ) ?: return@transactional AppResult.Error(VehicleError.LICENSE_PLATE_TAKEN)
            syncService.recordChange(
                userId = adminUserId,
                entityType = SyncEntityType.VEHICLE,
                entityId = vehicle.id,
                operation = SyncOperation.UPSERT,
            )

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
        clearPictureUrl: Boolean,
    ): AppResult<Vehicle, VehicleError> {
        val trimmedPlate = licensePlate.trim()

        val (result, pictureKeyToDelete) =
            tx.transactional {
                val existing =
                    findEditableVehicle(adminUserId, companyId, vehicleId).fold(
                        onSuccess = { it },
                        onError = { return@transactional AppResult.Error(it) to null },
                    )

                // The company lock acquired by requireAdminAccess serializes service-level plate
                // updates and creates within this fleet; the unique index remains the final guard.
                if (trimmedPlate != existing.licensePlate && isPlateTaken(companyId, vehicleId, trimmedPlate)) {
                    return@transactional AppResult.Error(VehicleError.LICENSE_PLATE_TAKEN) to null
                }

                val pictureUpdate = existing.pictureUpdate(pictureUrl, clearPictureUrl)
                val updated =
                    vehicleRepository.updateDetails(
                        vehicleId = vehicleId,
                        licensePlate = trimmedPlate,
                        pictureUrl = pictureUpdate.url,
                        pictureStorageKey = pictureUpdate.storageKey,
                    ) ?: return@transactional AppResult.Error(VehicleError.VEHICLE_NOT_FOUND) to null
                syncService.recordChange(
                    userId = adminUserId,
                    entityType = SyncEntityType.VEHICLE,
                    entityId = vehicleId,
                    operation = SyncOperation.UPSERT,
                )

                AppResult.Success(updated) to pictureUpdate.storageKeyToDelete
            }

        if (result is AppResult.Success) {
            pictureKeyToDelete?.let { imageStorage.deleteBestEffort(listOf(it)) }
        }
        return result
    }

    private suspend fun findEditableVehicle(
        adminUserId: Int,
        companyId: Int,
        vehicleId: Int,
    ): AppResult<Vehicle, VehicleError> {
        requireAdminAccess(adminUserId, companyId)?.let { return AppResult.Error(it) }
        val existing =
            vehicleRepository.findByIdForUpdate(vehicleId)
                ?: return AppResult.Error(VehicleError.VEHICLE_NOT_FOUND)

        return if (existing.companyId == companyId && existing.archivedAt == null) {
            AppResult.Success(existing)
        } else {
            AppResult.Error(VehicleError.VEHICLE_NOT_FOUND)
        }
    }

    private suspend fun isPlateTaken(
        companyId: Int,
        vehicleId: Int,
        licensePlate: String,
    ): Boolean = vehicleRepository.findByCompanyAndPlate(companyId, licensePlate)?.run { id != vehicleId } == true

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
            val vehicle = vehicleRepository.findById(vehicleId)
            if (vehicle == null || vehicle.archivedAt != null) {
                AppResult.Error(VehicleError.VEHICLE_NOT_FOUND)
            } else {
                AppResult.Success(vehicle)
            }
        }

    @Suppress("ReturnCount")
    override suspend fun archiveVehicle(
        adminUserId: Int,
        companyId: Int,
        vehicleId: Int,
    ): AppResult<Unit, VehicleError> {
        val (result, endedAssignment) =
            tx.transactional {
                requireAdminAccess(adminUserId, companyId)?.let {
                    return@transactional AppResult.Error(it) to null
                }

                val existing =
                    vehicleRepository.findByIdForUpdate(vehicleId)
                        ?: return@transactional AppResult.Error(VehicleError.VEHICLE_NOT_FOUND) to null
                if (existing.companyId != companyId) {
                    return@transactional AppResult.Error(VehicleError.VEHICLE_NOT_FOUND) to null
                }

                // Already archived: a clean no-op success — nothing left to do, since an archived
                // vehicle can never gain a new active assignment (see VehicleAssignmentError.
                // VEHICLE_ARCHIVED in link()), so there's nothing to force-end a second time either.
                if (existing.archivedAt != null) return@transactional AppResult.Success(Unit) to null

                // Defer both sync events until the assignment state and vehicle archive are complete.
                // The batch acquires per-user sync locks in stable user-id order, avoiding lock
                // inversion with other multi-user mutations such as company deletion.
                val endedAssignment =
                    when (val assignmentResult = endActiveAssignmentBeforeArchive(vehicleId, companyId)) {
                        is AppResult.Error -> return@transactional assignmentResult to null
                        is AppResult.Success -> assignmentResult.data
                    }

                check(vehicleRepository.archive(vehicleId)) {
                    "Vehicle archive failed after the company row was locked"
                }
                val syncChanges =
                    buildList {
                        endedAssignment?.let { assignment ->
                            add(
                                SyncChange(
                                    userId = assignment.vendorUserId,
                                    entityType = SyncEntityType.VEHICLE_ASSIGNMENT,
                                    entityId = assignment.id,
                                    operation = SyncOperation.UPSERT,
                                ),
                            )
                        }
                        add(
                            SyncChange(
                                userId = adminUserId,
                                entityType = SyncEntityType.VEHICLE,
                                entityId = vehicleId,
                                operation = SyncOperation.UPSERT,
                            ),
                        )
                    }
                syncService.recordChanges(syncChanges)

                AppResult.Success(Unit) to endedAssignment
            }

        if (result is AppResult.Success) {
            endedAssignment?.let {
                vehicleAssignmentService.notifyEndedAssignments(
                    listOf(it),
                )
            }
        }
        return result
    }

    private suspend fun endActiveAssignmentBeforeArchive(
        vehicleId: Int,
        companyId: Int,
    ): AppResult<VehicleAssignment?, VehicleError> {
        val activeAssignment =
            vehicleAssignmentService.findActiveForVehicle(vehicleId)
                ?: return AppResult.Success(null)
        val endedAssignment =
            vehicleAssignmentService.endActiveAssignmentForVendorInCompany(activeAssignment.vendorUserId, companyId)
        if (endedAssignment != null) return AppResult.Success(endedAssignment)

        // A concurrent link may have changed the assignment while this vehicle was being archived.
        val remainingAssignment =
            vehicleAssignmentService.findActiveForVehicle(vehicleId) ?: return AppResult.Success(null)
        val retriedAssignment =
            vehicleAssignmentService.endActiveAssignmentForVendorInCompany(remainingAssignment.vendorUserId, companyId)
        return retriedAssignment?.let { AppResult.Success(it) }
            ?: AppResult.Error(VehicleError.VEHICLE_NOT_FOUND)
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

        virusScanner.scan(rawBytes).toVehicleErrorOrNull()?.let { return AppResult.Error(it) }

        val sanitized =
            when (val result = imageSanitizer.sanitize(rawBytes)) {
                is AppResult.Error -> return AppResult.Error(VehicleError.INVALID_IMAGE)
                is AppResult.Success -> result.data
            }

        val newStorageKey = pictureStorageKey(vehicleId, UUID.randomUUID())
        imageStorage.store(newStorageKey, sanitized.bytes)

        // Keep the new key if the transaction throws: a lost commit response makes it impossible
        // to know whether the database now points at this object.
        val transactionResult =
            runSuspendCatching {
                tx.transactional {
                    checkCanUploadPicture(adminUserId, companyId, vehicleId)?.let {
                        return@transactional AppResult.Error(it) to null
                    }
                    val existing =
                        vehicleRepository.findByIdForUpdate(vehicleId)
                            ?: return@transactional AppResult.Error(VehicleError.VEHICLE_NOT_FOUND) to null
                    val previousStorageKey = existing.pictureStorageKey
                    val updated =
                        vehicleRepository.updatePictureUrl(
                            vehicleId = vehicleId,
                            pictureUrl = pictureRoute(vehicleId),
                            pictureStorageKey = newStorageKey,
                        ) ?: return@transactional AppResult.Error(VehicleError.VEHICLE_NOT_FOUND) to null
                    syncService.recordChange(
                        userId = adminUserId,
                        entityType = SyncEntityType.VEHICLE,
                        entityId = vehicleId,
                        operation = SyncOperation.UPSERT,
                    )

                    AppResult.Success(updated) to previousStorageKey
                }
            }

        if (transactionResult.isFailure) {
            val failure = requireNotNull(transactionResult.exceptionOrNull())
            deletePictureKeyIfUnreferenced(vehicleId, newStorageKey)
            throw failure
        }
        val (result, previousStorageKey) = transactionResult.getOrThrow()

        if (result is AppResult.Success) {
            if (previousStorageKey != null && previousStorageKey != newStorageKey) {
                imageStorage.deleteBestEffort(listOf(previousStorageKey))
            }
        } else {
            imageStorage.deleteBestEffort(listOf(newStorageKey))
        }
        return result
    }

    private suspend fun deletePictureKeyIfUnreferenced(
        vehicleId: Int,
        storageKey: String,
    ) {
        val currentStorageKeyResult =
            runSuspendCatching { tx.transactional { vehicleRepository.findById(vehicleId)?.pictureStorageKey } }
        if (currentStorageKeyResult.isFailure) {
            currentStorageKeyResult.exceptionOrNull()?.let { failure ->
                logger.warn(failure) {
                    "Could not verify whether an uploaded image is referenced for vehicleId=$vehicleId; preserving it"
                }
            }
            return
        }

        val currentStorageKey = currentStorageKeyResult.getOrThrow()
        if (currentStorageKey != storageKey) imageStorage.deleteBestEffort(listOf(storageKey))
    }

    override suspend fun getVehiclePicture(vehicleId: Int): AppResult<ByteArray, VehicleError> =
        findVehicle(vehicleId).fold(
            onSuccess = { vehicle ->
                if (vehicle.pictureUrl != pictureRoute(vehicleId)) {
                    AppResult.Error(VehicleError.VEHICLE_NOT_FOUND)
                } else {
                    val storageKey = vehicle.pictureStorageKey
                    if (storageKey == null) {
                        AppResult.Error(VehicleError.VEHICLE_NOT_FOUND)
                    } else {
                        val bytes = imageStorage.read(storageKey)
                        if (bytes == null) AppResult.Error(VehicleError.VEHICLE_NOT_FOUND) else AppResult.Success(bytes)
                    }
                }
            },
            onError = { AppResult.Error(it) },
        )

    private suspend fun checkCanUploadPicture(
        adminUserId: Int,
        companyId: Int,
        vehicleId: Int,
    ): VehicleError? {
        requireAdminAccess(adminUserId, companyId)?.let { return it }

        val existing = vehicleRepository.findByIdForUpdate(vehicleId) ?: return VehicleError.VEHICLE_NOT_FOUND
        return VehicleError.VEHICLE_NOT_FOUND.takeIf { existing.companyId != companyId || existing.archivedAt != null }
    }

    private suspend fun requireAdminAccess(
        adminUserId: Int,
        companyId: Int,
    ): VehicleError? {
        if (companyRepository.findByIdForUpdate(companyId) == null) return VehicleError.NOT_A_MEMBER

        return when (membershipRepository.requireAdminMembership(companyId, adminUserId)) {
            MembershipCheck.NotAMember -> VehicleError.NOT_A_MEMBER
            MembershipCheck.NotAdmin -> VehicleError.NOT_ADMIN
            is MembershipCheck.Admin -> null
        }
    }
}

private fun VirusScanResult.toVehicleErrorOrNull(): VehicleError? =
    when (this) {
        VirusScanResult.Clean -> null
        is VirusScanResult.Infected -> VehicleError.INVALID_IMAGE
        VirusScanResult.Unavailable -> VehicleError.VIRUS_SCANNER_UNAVAILABLE
    }
