package com.shelflife.feature.pantryEntryImage.service

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImageError
import com.shelflife.feature.sync.domain.model.SyncOperation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class PantryEntryImageServiceDeleteTest {
    @Test
    fun `deleting an image the caller owns removes it and purges the physical file`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            val image = harness.pantryEntryImageRepository.seed(pantryEntryId = entry.id, storageKey = "key-1")
            harness.imageStorage.stored["key-1"] = byteArrayOf(1)

            val result =
                harness.service.deleteImage(
                    userId = 1,
                    entryId = entry.id,
                    imageId = image.id,
                    originDeviceId = null,
                )

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertEquals(listOf("key-1"), harness.imageStorage.deletedKeys)
            assertEquals(null, harness.pantryEntryImageRepository.findByIdAndEntryId(image.id, entry.id))
        }
    }

    @Test
    fun `deleting an image records a delete sync change`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            val image = harness.pantryEntryImageRepository.seed(pantryEntryId = entry.id)

            harness.service.deleteImage(userId = 1, entryId = entry.id, imageId = image.id, originDeviceId = null)

            assertEquals(
                SyncOperation.DELETE,
                harness.syncService.recorded
                    .single()
                    .operation,
            )
            assertEquals(
                image.id,
                harness.syncService.recorded
                    .single()
                    .entityId,
            )
        }
    }

    @Test
    fun `deleting an image that doesn't exist is not found`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)

            val result =
                harness.service.deleteImage(
                    userId = 1,
                    entryId = entry.id,
                    imageId = 999,
                    originDeviceId = null,
                )

            assertEquals(AppResult.Error(PantryEntryImageError.NOT_FOUND), result)
        }
    }

    @Test
    fun `deleting an image on an entry owned by another user is rejected without touching storage`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 2)
            val image = harness.pantryEntryImageRepository.seed(pantryEntryId = entry.id, storageKey = "key-1")
            harness.imageStorage.stored["key-1"] = byteArrayOf(1)

            val result =
                harness.service.deleteImage(
                    userId = 1,
                    entryId = entry.id,
                    imageId = image.id,
                    originDeviceId = null,
                )

            assertEquals(AppResult.Error(PantryEntryImageError.ENTRY_NOT_FOUND), result)
            assertEquals(emptyList(), harness.imageStorage.deletedKeys)
        }
    }
}
