package com.shelflife.feature.pantryEntryImage.service

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImageError
import com.shelflife.feature.sync.domain.model.SyncOperation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

class PantryEntryImageServiceReplaceTest {
    @Test
    fun `replacing an image keeps the same id but stores new bytes under a new key`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            val existing = harness.pantryEntryImageRepository.seed(pantryEntryId = entry.id, storageKey = "old-key")
            harness.imageStorage.stored["old-key"] = byteArrayOf(1)
            harness.imageSanitizer.result =
                AppResult.Success(FakeImageSanitizer.sampleSanitizedImage(bytes = byteArrayOf(5, 5, 5)))

            val result =
                harness.service.replaceImage(
                    userId = 1,
                    entryId = entry.id,
                    imageId = existing.id,
                    rawBytes = byteArrayOf(2),
                    originDeviceId = null,
                )

            val updated = (result as? AppResult.Success)?.data ?: fail("expected success but got $result")
            assertEquals(existing.id, updated.id)
            assertEquals(byteArrayOf(5, 5, 5).toList(), harness.imageStorage.stored[updated.storageKey]?.toList())
        }
    }

    @Test
    fun `replacing an image purges the old physical file`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            val existing = harness.pantryEntryImageRepository.seed(pantryEntryId = entry.id, storageKey = "old-key")
            harness.imageStorage.stored["old-key"] = byteArrayOf(1)

            harness.service.replaceImage(
                userId = 1,
                entryId = entry.id,
                imageId = existing.id,
                rawBytes = byteArrayOf(2),
                originDeviceId = null,
            )

            assertEquals(listOf("old-key"), harness.imageStorage.deletedKeys)
            assertNull(harness.imageStorage.stored["old-key"])
        }
    }

    @Test
    fun `replacing records an upsert sync change`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            val existing = harness.pantryEntryImageRepository.seed(pantryEntryId = entry.id)

            harness.service.replaceImage(
                userId = 1,
                entryId = entry.id,
                imageId = existing.id,
                rawBytes = byteArrayOf(2),
                originDeviceId = null,
            )

            assertEquals(
                SyncOperation.UPSERT,
                harness.syncService.recorded
                    .single()
                    .operation,
            )
        }
    }

    @Test
    fun `replacing an image that doesn't belong to the entry is not found`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            val otherEntry = harness.pantryEntryRepository.seed(userId = 1)
            val imageOnOtherEntry = harness.pantryEntryImageRepository.seed(pantryEntryId = otherEntry.id)

            val result =
                harness.service.replaceImage(
                    userId = 1,
                    entryId = entry.id,
                    imageId = imageOnOtherEntry.id,
                    rawBytes = byteArrayOf(2),
                    originDeviceId = null,
                )

            assertEquals(AppResult.Error(PantryEntryImageError.NOT_FOUND), result)
        }
    }

    @Test
    fun `a row that vanishes between the existence check and the write purges the newly-stored file`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            val existing = harness.pantryEntryImageRepository.seed(pantryEntryId = entry.id, storageKey = "old-key")
            harness.imageStorage.stored["old-key"] = byteArrayOf(1)
            harness.pantryEntryImageRepository.failNextUpdateContent = true

            val result =
                harness.service.replaceImage(
                    userId = 1,
                    entryId = entry.id,
                    imageId = existing.id,
                    rawBytes = byteArrayOf(2),
                    originDeviceId = null,
                )

            assertEquals(AppResult.Error(PantryEntryImageError.NOT_FOUND), result)
            assertEquals(1, harness.imageStorage.deletedKeys.size)
            assertEquals(setOf("old-key"), harness.imageStorage.stored.keys)
        }
    }

    @Test
    fun `a storage backend that refuses the new content is surfaced as STORAGE_UNAVAILABLE, old image untouched`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            val existing = harness.pantryEntryImageRepository.seed(pantryEntryId = entry.id, storageKey = "old-key")
            harness.imageStorage.stored["old-key"] = byteArrayOf(1)
            harness.imageStorage.failNextStore = true

            val result =
                harness.service.replaceImage(
                    userId = 1,
                    entryId = entry.id,
                    imageId = existing.id,
                    rawBytes = byteArrayOf(2),
                    originDeviceId = null,
                )

            assertEquals(AppResult.Error(PantryEntryImageError.STORAGE_UNAVAILABLE), result)
            assertEquals(byteArrayOf(1).toList(), harness.imageStorage.stored["old-key"]?.toList())
            assertEquals(emptyList(), harness.imageStorage.deletedKeys)
            assertEquals(
                "old-key",
                harness.pantryEntryImageRepository.findByIdAndEntryId(existing.id, entry.id)?.storageKey,
            )
        }
    }

    @Test
    fun `replacing an image on an entry owned by another user is rejected before touching any image`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 2)
            val existing = harness.pantryEntryImageRepository.seed(pantryEntryId = entry.id, storageKey = "old-key")
            harness.imageStorage.stored["old-key"] = byteArrayOf(1)

            val result =
                harness.service.replaceImage(
                    userId = 1,
                    entryId = entry.id,
                    imageId = existing.id,
                    rawBytes = byteArrayOf(2),
                    originDeviceId = null,
                )

            assertEquals(AppResult.Error(PantryEntryImageError.ENTRY_NOT_FOUND), result)
            assertEquals(emptyList(), harness.imageStorage.deletedKeys)
        }
    }
}
