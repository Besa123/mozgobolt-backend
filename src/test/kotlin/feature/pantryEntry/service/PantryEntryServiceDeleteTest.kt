package com.shelflife.feature.pantryEntry.service

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryError
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class PantryEntryServiceDeleteTest {
    @Test
    fun `deleting an entry the caller owns succeeds`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)

            val result = harness.service.deleteEntry(userId = 1, entryId = entry.id)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
        }
    }

    @Test
    fun `deleting an entry owned by another user is not found`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 2)

            val result = harness.service.deleteEntry(userId = 1, entryId = entry.id)

            assertEquals(AppResult.Error(PantryEntryError.NOT_FOUND), result)
        }
    }

    @Test
    fun `deleting an entry purges its images from storage`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            harness.pantryEntryImageCleanup.storageKeysToReturn = listOf("key-1", "key-2")
            harness.imageStorage.stored["key-1"] = byteArrayOf(1)
            harness.imageStorage.stored["key-2"] = byteArrayOf(2)

            harness.service.deleteEntry(userId = 1, entryId = entry.id)

            assertEquals(listOf(entry.id), harness.pantryEntryImageCleanup.calledForEntryIds)
            assertEquals(setOf("key-1", "key-2"), harness.imageStorage.deletedKeys.toSet())
        }
    }

    @Test
    fun `deleting an entry owned by another user never touches its images`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 2)

            harness.service.deleteEntry(userId = 1, entryId = entry.id)

            assertEquals(emptyList(), harness.pantryEntryImageCleanup.calledForEntryIds)
        }
    }
}
