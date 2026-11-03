/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicle.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.media.ImageSanitizationError
import com.mozgobolt.core.domain.media.ImageSanitizer
import com.mozgobolt.core.domain.media.ImageStorage
import com.mozgobolt.core.domain.media.SanitizedImage
import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.company.data.repository.CompanyMembershipRepositoryI
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.sync.domain.SyncService
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.sync.routing.FakeSyncService
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class VehiclePictureRealDatabaseTest {
    private class CoordinatedImageStorage : ImageStorage {
        val stored = ConcurrentHashMap<String, ByteArray>()
        val bothStoresStarted = CompletableDeferred<Unit>()
        val continueStores = CompletableDeferred<Unit>()
        private val storeCount = AtomicInteger()

        override suspend fun store(
            key: String,
            bytes: ByteArray,
        ) {
            stored[key] = bytes.copyOf()
            if (storeCount.incrementAndGet() == 2) bothStoresStarted.complete(Unit)
            continueStores.await()
        }

        override suspend fun read(key: String): ByteArray? = stored[key]?.copyOf()

        override suspend fun deleteBestEffort(keys: List<String>) {
            keys.forEach(stored::remove)
        }
    }

    private class PassThroughImageSanitizer : ImageSanitizer {
        override suspend fun sanitize(rawBytes: ByteArray): AppResult<SanitizedImage, ImageSanitizationError> =
            AppResult.Success(
                SanitizedImage(
                    bytes = rawBytes.copyOf(),
                    width = 1,
                    height = 1,
                    contentType = "image/jpeg",
                ),
            )
    }

    private class FailingPictureSyncService : SyncService by FakeSyncService() {
        override suspend fun recordChange(
            userId: Int,
            entityType: SyncEntityType,
            entityId: Int,
            operation: SyncOperation,
            originDeviceId: String?,
        ) {
            error("simulated picture sync persistence failure")
        }
    }

    @Test
    fun `concurrent picture uploads keep the committed storage key and object aligned`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val companyRepository = CompanyRepositoryI()
            val membershipRepository = CompanyMembershipRepositoryI()
            val vehicleRepository = VehicleRepositoryI()
            val (companyId, vehicleId) =
                createAdminVehicle(
                    tx = tx,
                    companyRepository = companyRepository,
                    membershipRepository = membershipRepository,
                    vehicleRepository = vehicleRepository,
                    plate = "PICTURE-PLATE",
                )
            val imageStorage = CoordinatedImageStorage()
            val service =
                createVehicleService(
                    companyRepository = companyRepository,
                    membershipRepository = membershipRepository,
                    vehicleRepository = vehicleRepository,
                    tx = tx,
                    syncService = FakeSyncService(),
                    imageStorage = imageStorage,
                    imageSanitizer = PassThroughImageSanitizer(),
                )

            val outcomes =
                coroutineScope {
                    val first =
                        async(Dispatchers.IO) {
                            service.uploadVehiclePicture(1, companyId, vehicleId, byteArrayOf(1))
                        }
                    val second =
                        async(Dispatchers.IO) {
                            service.uploadVehiclePicture(1, companyId, vehicleId, byteArrayOf(2))
                        }

                    try {
                        withTimeout(5_000) { imageStorage.bothStoresStarted.await() }
                    } finally {
                        imageStorage.continueStores.complete(Unit)
                    }

                    listOf(first.await(), second.await())
                }
            val uploadedKeys =
                outcomes
                    .map { outcome ->
                        requireNotNull(
                            outcome.fold(
                                onSuccess = { it.pictureStorageKey },
                                onError = { fail("expected concurrent upload success but got $it") },
                            ),
                        )
                    }.toSet()
            assertEquals(2, uploadedKeys.size)

            val persistedVehicle = requireNotNull(tx.transactional { vehicleRepository.findById(vehicleId) })
            val persistedKey = requireNotNull(persistedVehicle.pictureStorageKey)
            assertTrue(persistedKey in uploadedKeys)
            assertEquals(setOf(persistedKey), imageStorage.stored.keys)
            assertContentEquals(
                imageStorage.stored[persistedKey],
                service.getVehiclePicture(vehicleId).fold(
                    onSuccess = { it },
                    onError = { fail("expected committed picture but got $it") },
                ),
            )
        }
    }

    @Test
    fun `a database rollback during replacement keeps the previous picture active`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val companyRepository = CompanyRepositoryI()
            val membershipRepository = CompanyMembershipRepositoryI()
            val vehicleRepository = VehicleRepositoryI()
            val (companyId, vehicleId) =
                createAdminVehicle(
                    tx = tx,
                    companyRepository = companyRepository,
                    membershipRepository = membershipRepository,
                    vehicleRepository = vehicleRepository,
                    plate = "PICTURE-ROLLBACK",
                )
            val imageStorage = FakeImageStorage()
            val firstUploadService =
                createVehicleService(
                    companyRepository = companyRepository,
                    membershipRepository = membershipRepository,
                    vehicleRepository = vehicleRepository,
                    tx = tx,
                    syncService = FakeSyncService(),
                    imageStorage = imageStorage,
                )
            val firstUpload =
                firstUploadService.uploadVehiclePicture(1, companyId, vehicleId, byteArrayOf(1)).fold(
                    onSuccess = { it },
                    onError = { fail("initial picture upload failed: $it") },
                )
            val firstStorageKey = requireNotNull(firstUpload.pictureStorageKey)
            val failingUploadService =
                createVehicleService(
                    companyRepository = companyRepository,
                    membershipRepository = membershipRepository,
                    vehicleRepository = vehicleRepository,
                    tx = tx,
                    syncService = FailingPictureSyncService(),
                    imageStorage = imageStorage,
                )

            assertFailsWith<IllegalStateException> {
                failingUploadService.uploadVehiclePicture(1, companyId, vehicleId, byteArrayOf(2))
            }

            val persistedVehicle = requireNotNull(tx.transactional { vehicleRepository.findById(vehicleId) })
            assertEquals(firstStorageKey, persistedVehicle.pictureStorageKey)
            assertEquals(setOf(firstStorageKey), imageStorage.stored.keys)
            assertContentEquals(
                imageStorage.stored[firstStorageKey],
                failingUploadService.getVehiclePicture(vehicleId).fold(
                    onSuccess = { it },
                    onError = { fail("previous picture should remain available: $it") },
                ),
            )
        }
    }

    private suspend fun createAdminVehicle(
        tx: TransactionalRunner,
        companyRepository: CompanyRepositoryI,
        membershipRepository: CompanyMembershipRepositoryI,
        vehicleRepository: VehicleRepositoryI,
        plate: String,
    ): Pair<Int, Int> =
        tx.transactional {
            val company =
                companyRepository.create(
                    name = "Vehicle picture test",
                    inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                )
            membershipRepository.addIfAbsent(company.id, 1, CompanyRole.ADMIN)
            val vehicle = checkNotNull(vehicleRepository.create(company.id, "Picture truck", plate))
            company.id to vehicle.id
        }

    @Suppress("LongParameterList")
    private fun createVehicleService(
        companyRepository: CompanyRepositoryI,
        membershipRepository: CompanyMembershipRepositoryI,
        vehicleRepository: VehicleRepositoryI,
        tx: TransactionalRunner,
        syncService: SyncService,
        imageStorage: ImageStorage,
        imageSanitizer: ImageSanitizer = FakeImageSanitizer(),
    ) = VehicleServiceI(
        companyRepository = companyRepository,
        vehicleRepository = vehicleRepository,
        membershipRepository = membershipRepository,
        vehicleAssignmentService = FakeVehicleAssignmentService(),
        syncService = syncService,
        imageSanitizer = imageSanitizer,
        virusScanner = FakeVirusScanner(),
        imageStorage = imageStorage,
        tx = tx,
    )
}
