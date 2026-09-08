package com.shelflife.feature.pantryEntryImage.service

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImageError
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class PantryEntryImageServiceQueryTest {
    @Test
    fun `listing images for an entry the caller owns returns them in insertion order`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            val first = harness.pantryEntryImageRepository.seed(pantryEntryId = entry.id)
            val second = harness.pantryEntryImageRepository.seed(pantryEntryId = entry.id)

            val result = harness.service.listImages(userId = 1, entryId = entry.id)

            val images = (result as? AppResult.Success)?.data ?: fail("expected success but got $result")
            assertEquals(listOf(first.id, second.id), images.map { it.id })
        }
    }

    @Test
    fun `listing images for an entry owned by another user is rejected`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 2)

            val result = harness.service.listImages(userId = 1, entryId = entry.id)

            assertEquals(AppResult.Error(PantryEntryImageError.ENTRY_NOT_FOUND), result)
        }
    }

    @Test
    fun `loading content returns the stored bytes with a content hash ETag`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            val image = harness.pantryEntryImageRepository.seed(pantryEntryId = entry.id, storageKey = "key-1")
            harness.imageStorage.stored["key-1"] = byteArrayOf(7, 7, 7)

            val result = harness.service.loadContent(userId = 1, entryId = entry.id, imageId = image.id)

            val content = (result as? AppResult.Success)?.data ?: fail("expected success but got $result")
            assertEquals(byteArrayOf(7, 7, 7).toList(), content.bytes.toList())
            assertEquals("image/jpeg", content.contentType)
            assertEquals(64, content.eTag.length, "expected a hex-encoded SHA-256 digest")
        }
    }

    @Test
    fun `loading content for a row whose physical file is missing is not found, not a crash`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            val image = harness.pantryEntryImageRepository.seed(pantryEntryId = entry.id, storageKey = "missing-key")

            val result = harness.service.loadContent(userId = 1, entryId = entry.id, imageId = image.id)

            assertEquals(AppResult.Error(PantryEntryImageError.NOT_FOUND), result)
        }
    }

    @Test
    fun `a storage backend that refuses to read is surfaced as STORAGE_UNAVAILABLE, distinct from not-found`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            val image = harness.pantryEntryImageRepository.seed(pantryEntryId = entry.id, storageKey = "key-1")
            harness.imageStorage.stored["key-1"] = byteArrayOf(7)
            harness.imageStorage.failNextRead = true

            val result = harness.service.loadContent(userId = 1, entryId = entry.id, imageId = image.id)

            assertEquals(AppResult.Error(PantryEntryImageError.STORAGE_UNAVAILABLE), result)
        }
    }
}
