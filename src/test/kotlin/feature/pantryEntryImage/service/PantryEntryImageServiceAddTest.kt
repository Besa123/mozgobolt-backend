package com.shelflife.feature.pantryEntryImage.service

import com.shelflife.core.domain.AppResult
import com.shelflife.core.domain.media.ImageSanitizationError
import com.shelflife.core.domain.security.VirusScanResult
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImageError
import com.shelflife.feature.sync.domain.model.SyncEntityType
import com.shelflife.feature.sync.domain.model.SyncOperation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class PantryEntryImageServiceAddTest {
    @Test
    fun `adding an image to an entry the caller owns succeeds and stores the sanitized bytes`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            harness.imageSanitizer.result =
                AppResult.Success(FakeImageSanitizer.sampleSanitizedImage(bytes = byteArrayOf(9, 9, 9)))

            val result =
                harness.service.addImage(
                    userId = 1,
                    entryId = entry.id,
                    rawBytes = byteArrayOf(1),
                    originDeviceId = null,
                )

            val image = (result as? AppResult.Success)?.data ?: fail("expected success but got $result")
            assertEquals(10, image.width)
            assertEquals(20, image.height)
            assertEquals(byteArrayOf(9, 9, 9).toList(), harness.imageStorage.stored[image.storageKey]?.toList())
        }
    }

    @Test
    fun `adding an image records a sync change for the new image`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)

            val result =
                harness.service.addImage(
                    userId = 1,
                    entryId = entry.id,
                    rawBytes = byteArrayOf(1),
                    originDeviceId = "device-1",
                )

            val image = (result as? AppResult.Success)?.data ?: fail("expected success but got $result")
            val recorded = harness.syncService.recorded.single()
            assertEquals(SyncEntityType.PANTRY_ENTRY_IMAGE, recorded.entityType)
            assertEquals(image.id, recorded.entityId)
            assertEquals(SyncOperation.UPSERT, recorded.operation)
            assertEquals("device-1", harness.syncService.lastRecordedOriginDeviceId)
        }
    }

    @Test
    fun `adding an image to an entry owned by another user is rejected`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 2)

            val result =
                harness.service.addImage(
                    userId = 1,
                    entryId = entry.id,
                    rawBytes = byteArrayOf(1),
                    originDeviceId = null,
                )

            assertEquals(AppResult.Error(PantryEntryImageError.ENTRY_NOT_FOUND), result)
        }
    }

    @Test
    fun `adding a fourth image beyond the configured cap is rejected`() {
        runBlocking {
            val harness = newHarness(maxImagesPerEntry = 3)
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            repeat(3) { harness.pantryEntryImageRepository.seed(pantryEntryId = entry.id) }

            val result =
                harness.service.addImage(
                    userId = 1,
                    entryId = entry.id,
                    rawBytes = byteArrayOf(1),
                    originDeviceId = null,
                )

            assertEquals(AppResult.Error(PantryEntryImageError.TOO_MANY_IMAGES), result)
        }
    }

    @Test
    fun `a sanitizer rejection is mapped to the matching domain error`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            harness.imageSanitizer.result = AppResult.Error(ImageSanitizationError.UNSUPPORTED_FORMAT)

            val result =
                harness.service.addImage(
                    userId = 1,
                    entryId = entry.id,
                    rawBytes = byteArrayOf(1),
                    originDeviceId = null,
                )

            assertEquals(AppResult.Error(PantryEntryImageError.UNSUPPORTED_FORMAT), result)
        }
    }

    @Test
    fun `an infected upload is rejected when ClamAV is enabled`() {
        runBlocking {
            val harness = newHarness(clamAvEnabled = true)
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            harness.virusScanner.result = VirusScanResult.Infected("EICAR-Test")

            val result =
                harness.service.addImage(
                    userId = 1,
                    entryId = entry.id,
                    rawBytes = byteArrayOf(1),
                    originDeviceId = null,
                )

            assertEquals(AppResult.Error(PantryEntryImageError.MALWARE_DETECTED), result)
        }
    }

    @Test
    fun `an unreachable scanner fails closed when ClamAV is enabled`() {
        runBlocking {
            val harness = newHarness(clamAvEnabled = true)
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            harness.virusScanner.result = VirusScanResult.Unavailable

            val result =
                harness.service.addImage(
                    userId = 1,
                    entryId = entry.id,
                    rawBytes = byteArrayOf(1),
                    originDeviceId = null,
                )

            assertEquals(AppResult.Error(PantryEntryImageError.SCAN_UNAVAILABLE), result)
        }
    }

    @Test
    fun `the image count is re-checked immediately before the insert, not only in the pre-check`() {
        runBlocking {
            val harness = newHarness(maxImagesPerEntry = 3)
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            repeat(2) { harness.pantryEntryImageRepository.seed(pantryEntryId = entry.id) }

            harness.service.addImage(userId = 1, entryId = entry.id, rawBytes = byteArrayOf(1), originDeviceId = null)

            assertEquals(
                2,
                harness.pantryEntryImageRepository.countByEntryIdCallCount,
                "expected the cap to be checked once as a pre-check and again inside the write transaction",
            )
        }
    }

    @Test
    fun `a storage backend that refuses the upload is surfaced as STORAGE_UNAVAILABLE, not a crash`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            harness.imageStorage.failNextStore = true

            val result =
                harness.service.addImage(
                    userId = 1,
                    entryId = entry.id,
                    rawBytes = byteArrayOf(1),
                    originDeviceId = null,
                )

            assertEquals(AppResult.Error(PantryEntryImageError.STORAGE_UNAVAILABLE), result)
        }
    }

    @Test
    fun `a rejected upload never creates a database row or a sync event`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            harness.imageStorage.failNextStore = true

            harness.service.addImage(userId = 1, entryId = entry.id, rawBytes = byteArrayOf(1), originDeviceId = null)

            assertEquals(emptyList(), harness.pantryEntryImageRepository.listByEntryId(entry.id))
            assertEquals(emptyList(), harness.syncService.recorded)
        }
    }

    @Test
    fun `the scanner is never consulted when ClamAV is disabled, even if it would flag the upload`() {
        runBlocking {
            val harness = newHarness(clamAvEnabled = false)
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            harness.virusScanner.result = VirusScanResult.Infected("would-fail-if-checked")

            val result =
                harness.service.addImage(
                    userId = 1,
                    entryId = entry.id,
                    rawBytes = byteArrayOf(1),
                    originDeviceId = null,
                )

            assertEquals(true, result is AppResult.Success)
        }
    }
}
