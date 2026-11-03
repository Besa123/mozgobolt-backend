/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicle.service

import com.mozgobolt.core.data.media.LocalDiskImageStorage
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.media.ImageSanitizationError
import com.mozgobolt.core.domain.media.ImageSanitizer
import com.mozgobolt.core.domain.media.SanitizedImage
import com.mozgobolt.core.domain.security.VirusScanResult
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.vehicle.domain.model.VehicleError
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class VehicleServicePictureTest {
    @Test
    fun `an admin upload stores sanitized bytes publishes the serving route and can read the picture back`() {
        runBlocking {
            val imageStorage = FakeImageStorage()
            val fx = VehicleServiceFixture(imageStorage = imageStorage)
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
            val vehicle =
                fx.service.createVehicle(10, 1, "Truck", "PLATE-1").fold(
                    onSuccess = { it },
                    onError = { fail("$it") },
                )
            val rawBytes = byteArrayOf(9, 8, 7)

            val result =
                fx.service.uploadVehiclePicture(
                    adminUserId = 10,
                    companyId = 1,
                    vehicleId = vehicle.id,
                    rawBytes = rawBytes,
                )

            val updated = result.fold(onSuccess = { it }, onError = { fail("expected success but got $it") })
            assertEquals("/vehicles/${vehicle.id}/picture", updated.pictureUrl)
            val storageKey = requireNotNull(updated.pictureStorageKey)
            assertTrue(storageKey.startsWith("vehicles/${vehicle.id}/"))
            assertContentEquals(byteArrayOf(1), imageStorage.stored[storageKey])
            assertTrue(
                fx.syncService.recorded.any {
                    it.entityType == SyncEntityType.VEHICLE && it.entityId == vehicle.id
                },
            )

            val fetched =
                fx.service.getVehiclePicture(vehicle.id).fold(
                    onSuccess = { it },
                    onError = { fail("expected stored picture but got $it") },
                )
            assertContentEquals(byteArrayOf(1), fetched)

            imageStorage.stored.clear()
            fx.service.getVehiclePicture(vehicle.id).fold(
                onSuccess = { fail("expected missing stored picture but got ${it.size} bytes") },
                onError = { assertEquals(VehicleError.VEHICLE_NOT_FOUND, it) },
            )
        }
    }

    @Test
    fun `vehicle picture upload works with the default local storage key namespace`() =
        runBlocking {
            withTemporaryDirectory { directory ->
                val fx =
                    VehicleServiceFixture(
                        imageStorage = LocalDiskImageStorage(directory.toString()),
                    )
                fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
                val vehicle =
                    fx.service.createVehicle(10, 1, "Truck", "PLATE-LOCAL").fold(
                        onSuccess = { it },
                        onError = { fail("$it") },
                    )

                fx.service.uploadVehiclePicture(10, 1, vehicle.id, byteArrayOf(9, 8, 7)).fold(
                    onSuccess = { assertEquals("/vehicles/${vehicle.id}/picture", it.pictureUrl) },
                    onError = { fail("expected local upload success but got $it") },
                )

                val fetched =
                    fx.service.getVehiclePicture(vehicle.id).fold(
                        onSuccess = { it },
                        onError = { fail("expected stored picture but got $it") },
                    )
                assertContentEquals(byteArrayOf(1), fetched)
            }
        }

    @Test
    fun `a stored object is not served after the vehicle picture URL changes`() =
        runBlocking {
            val imageStorage = FakeImageStorage()
            val fx = VehicleServiceFixture(imageStorage = imageStorage)
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
            val vehicle =
                fx.service.createVehicle(10, 1, "Truck", "PLATE-EXTERNAL").fold(
                    onSuccess = { it },
                    onError = { fail("$it") },
                )
            fx.service.uploadVehiclePicture(10, 1, vehicle.id, byteArrayOf(9, 8, 7)).fold(
                onSuccess = {},
                onError = { fail("expected upload success but got $it") },
            )
            fx.service
                .updateVehicle(
                    adminUserId = 10,
                    companyId = 1,
                    vehicleId = vehicle.id,
                    licensePlate = vehicle.licensePlate,
                    pictureUrl = "https://example.com/truck.jpg",
                ).fold(
                    onSuccess = {},
                    onError = { fail("expected picture URL update but got $it") },
                )

            fx.service.getVehiclePicture(vehicle.id).fold(
                onSuccess = { fail("expected unlinked image to be unavailable but got ${it.size} bytes") },
                onError = { assertEquals(VehicleError.VEHICLE_NOT_FOUND, it) },
            )
            assertTrue(imageStorage.stored.isEmpty(), "replacing the picture URL should clean up its old object")
        }

    @Test
    fun `a picture replacement atomically switches to a new storage key and removes the old object`() =
        runBlocking {
            val imageStorage = FakeImageStorage()
            val sanitizer =
                object : ImageSanitizer {
                    override suspend fun sanitize(rawBytes: ByteArray) =
                        AppResult.Success(
                            SanitizedImage(
                                bytes = rawBytes,
                                width = 1,
                                height = 1,
                                contentType = "image/jpeg",
                            ),
                        )
                }
            val fx =
                VehicleServiceFixture(
                    imageSanitizer = sanitizer,
                    imageStorage = imageStorage,
                )
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
            val vehicle =
                fx.service.createVehicle(10, 1, "Truck", "PLATE-REPLACE").fold(
                    onSuccess = { it },
                    onError = { fail("$it") },
                )

            val first =
                fx.service.uploadVehiclePicture(10, 1, vehicle.id, byteArrayOf(1)).fold(
                    onSuccess = { it },
                    onError = { fail("expected first upload success but got $it") },
                )
            val firstKey = requireNotNull(first.pictureStorageKey)
            val second =
                fx.service.uploadVehiclePicture(10, 1, vehicle.id, byteArrayOf(2)).fold(
                    onSuccess = { it },
                    onError = { fail("expected replacement upload success but got $it") },
                )
            val secondKey = requireNotNull(second.pictureStorageKey)

            assertTrue(firstKey != secondKey)
            assertEquals(setOf(secondKey), imageStorage.stored.keys)
            assertContentEquals(
                byteArrayOf(2),
                fx.service.getVehiclePicture(vehicle.id).fold(
                    onSuccess = { it },
                    onError = { fail("expected current picture but got $it") },
                ),
            )
        }

    @Test
    fun `omitted pictureUrl preserves the image while explicit null clears it`() =
        runBlocking {
            val imageStorage = FakeImageStorage()
            val fx = VehicleServiceFixture(imageStorage = imageStorage)
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
            val vehicle =
                fx.service.createVehicle(10, 1, "Truck", "PLATE-PATCH").fold(
                    onSuccess = { it },
                    onError = { fail("$it") },
                )
            val uploaded =
                fx.service
                    .uploadVehiclePicture(10, 1, vehicle.id, byteArrayOf(1))
                    .fold(
                        onSuccess = { it },
                        onError = { fail("expected upload success but got $it") },
                    )
            val storageKey = requireNotNull(uploaded.pictureStorageKey)

            val plateUpdate =
                fx.service
                    .updateVehicle(
                        adminUserId = 10,
                        companyId = 1,
                        vehicleId = vehicle.id,
                        licensePlate = "PLATE-PATCH",
                        pictureUrl = null,
                        clearPictureUrl = false,
                    ).fold(
                        onSuccess = { it },
                        onError = { fail("expected plate update success but got $it") },
                    )
            assertEquals("/vehicles/${vehicle.id}/picture", plateUpdate.pictureUrl)
            assertEquals(storageKey, plateUpdate.pictureStorageKey)
            assertContentEquals(byteArrayOf(1), imageStorage.stored[storageKey])

            val pictureCleared =
                fx.service
                    .updateVehicle(
                        adminUserId = 10,
                        companyId = 1,
                        vehicleId = vehicle.id,
                        licensePlate = "PLATE-PATCH",
                        pictureUrl = null,
                        clearPictureUrl = true,
                    ).fold(
                        onSuccess = { it },
                        onError = { fail("expected picture clear success but got $it") },
                    )
            assertEquals(null, pictureCleared.pictureUrl)
            assertEquals(null, pictureCleared.pictureStorageKey)
            assertTrue(imageStorage.stored.isEmpty())
        }

    @Test
    fun `a non-clean virus scan rejects the upload before sanitizing or storing bytes`() {
        runBlocking {
            listOf(
                VirusScanResult.Infected("test-signature") to VehicleError.INVALID_IMAGE,
                VirusScanResult.Unavailable to VehicleError.VIRUS_SCANNER_UNAVAILABLE,
            ).forEach { (scanResult, expectedError) ->
                val imageStorage = FakeImageStorage()
                val fx =
                    VehicleServiceFixture(
                        virusScanner = FakeVirusScanner(scanResult),
                        imageStorage = imageStorage,
                    )
                fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
                val vehicle =
                    fx.service.createVehicle(10, 1, "Truck", "PLATE-1").fold(
                        onSuccess = { it },
                        onError = { fail("$it") },
                    )

                fx.service
                    .uploadVehiclePicture(10, 1, vehicle.id, byteArrayOf(1, 2, 3))
                    .fold(
                        onSuccess = { fail("expected $expectedError but got success") },
                        onError = { assertEquals(expectedError, it) },
                    )

                assertTrue(imageStorage.stored.isEmpty())
            }
        }
    }

    @Test
    fun `a sanitizer rejection prevents storing the source image`() {
        runBlocking {
            val imageStorage = FakeImageStorage()
            val fx =
                VehicleServiceFixture(
                    imageSanitizer =
                        FakeImageSanitizer(
                            AppResult.Error(ImageSanitizationError.CORRUPT),
                        ),
                    imageStorage = imageStorage,
                )
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
            val vehicle =
                fx.service.createVehicle(10, 1, "Truck", "PLATE-1").fold(
                    onSuccess = { it },
                    onError = { fail("$it") },
                )

            fx.service
                .uploadVehiclePicture(10, 1, vehicle.id, byteArrayOf(1, 2, 3))
                .fold(
                    onSuccess = { fail("expected INVALID_IMAGE but got success") },
                    onError = { assertEquals(VehicleError.INVALID_IMAGE, it) },
                )

            assertTrue(imageStorage.stored.isEmpty())
        }
    }

    @Test
    fun `picture uploads reject callers without admin membership and missing or archived vehicles`() {
        runBlocking {
            val nonMember = VehicleServiceFixture()
            nonMember.service.uploadVehiclePicture(10, 1, 999, byteArrayOf(1)).fold(
                onSuccess = { fail("expected NOT_A_MEMBER but got success") },
                onError = { assertEquals(VehicleError.NOT_A_MEMBER, it) },
            )

            val member = VehicleServiceFixture()
            member.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.MEMBER)
            member.service.uploadVehiclePicture(10, 1, 999, byteArrayOf(1)).fold(
                onSuccess = { fail("expected NOT_ADMIN but got success") },
                onError = { assertEquals(VehicleError.NOT_ADMIN, it) },
            )

            val admin = VehicleServiceFixture()
            admin.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
            admin.service.uploadVehiclePicture(10, 1, 999, byteArrayOf(1)).fold(
                onSuccess = { fail("expected VEHICLE_NOT_FOUND but got success") },
                onError = { assertEquals(VehicleError.VEHICLE_NOT_FOUND, it) },
            )

            val otherCompany = VehicleServiceFixture()
            otherCompany.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
            otherCompany.membershipRepository.seed(companyId = 2, userId = 20, role = CompanyRole.ADMIN)
            val foreignVehicle =
                otherCompany.service.createVehicle(20, 2, "Other truck", "PLATE-2").fold(
                    onSuccess = { it },
                    onError = { fail("$it") },
                )
            otherCompany.service.uploadVehiclePicture(10, 1, foreignVehicle.id, byteArrayOf(1)).fold(
                onSuccess = { fail("expected VEHICLE_NOT_FOUND but got success") },
                onError = { assertEquals(VehicleError.VEHICLE_NOT_FOUND, it) },
            )

            val archived = VehicleServiceFixture()
            archived.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
            val archivedVehicle =
                archived.service.createVehicle(10, 1, "Archived truck", "PLATE-3").fold(
                    onSuccess = { it },
                    onError = { fail("$it") },
                )
            archived.service.archiveVehicle(10, 1, archivedVehicle.id).fold(
                onSuccess = {},
                onError = { fail("$it") },
            )
            archived.service.uploadVehiclePicture(10, 1, archivedVehicle.id, byteArrayOf(1)).fold(
                onSuccess = { fail("expected VEHICLE_NOT_FOUND but got success") },
                onError = { assertEquals(VehicleError.VEHICLE_NOT_FOUND, it) },
            )
        }
    }

    private suspend fun withTemporaryDirectory(block: suspend (Path) -> Unit) {
        val directory = Files.createTempDirectory("vehicle-picture-test")
        try {
            block(directory)
        } finally {
            deleteDirectory(directory)
        }
    }

    private fun deleteDirectory(directory: Path) {
        Files.walk(directory).use { paths ->
            paths
                .sorted(Comparator.reverseOrder())
                .forEach { path -> Files.deleteIfExists(path) }
        }
    }
}
