package com.shelflife.feature.pantryEntry.service

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class PantryEntryServiceQueryTest {
    @Test
    fun `finding an entry the caller owns by id returns it`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)

            val result = harness.service.findByIdForUser(userId = 1, entryId = entry.id)

            assertEquals(entry.id, result?.id)
        }
    }

    @Test
    fun `finding an entry owned by another user returns null, never someone else's data`() {
        runBlocking {
            val harness = newHarness()
            val othersEntry = harness.pantryEntryRepository.seed(userId = 2)

            val result = harness.service.findByIdForUser(userId = 1, entryId = othersEntry.id)

            assertEquals(null, result)
        }
    }

    @Test
    fun `finding a nonexistent entry returns null`() {
        runBlocking {
            val harness = newHarness()

            val result = harness.service.findByIdForUser(userId = 1, entryId = 999)

            assertEquals(null, result)
        }
    }

    @Test
    fun `listing returns only the caller's own entries`() {
        runBlocking {
            val harness = newHarness()
            harness.pantryEntryRepository.seed(userId = 1)
            harness.pantryEntryRepository.seed(userId = 2)

            val results = harness.service.listForUser(userId = 1, afterId = null, limit = null).items

            assertEquals(1, results.size)
            assertEquals(1, results.single().userId)
        }
    }

    @Test
    fun `listForUser clamps a zero or negative limit up to at least one item per page`() {
        runBlocking {
            val harness = newHarness()
            harness.pantryEntryRepository.seed(userId = 1)
            harness.pantryEntryRepository.seed(userId = 1)

            val zeroLimit = harness.service.listForUser(userId = 1, afterId = null, limit = 0)
            val negativeLimit = harness.service.listForUser(userId = 1, afterId = null, limit = -5)

            assertEquals(1, zeroLimit.items.size)
            assertEquals(1, negativeLimit.items.size)
        }
    }

    @Test
    fun `listForUser clamps a limit above two hundred down to the documented maximum`() {
        runBlocking {
            val harness = newHarness()
            repeat(201) { harness.pantryEntryRepository.seed(userId = 1) }

            val page = harness.service.listForUser(userId = 1, afterId = null, limit = 500)

            assertEquals(200, page.items.size)
        }
    }

    @Test
    fun `listForUser defaults to a page size of fifty when no limit is given`() {
        runBlocking {
            val harness = newHarness()
            repeat(60) { harness.pantryEntryRepository.seed(userId = 1) }

            val page = harness.service.listForUser(userId = 1, afterId = null, limit = null)

            assertEquals(50, page.items.size)
            assertEquals(true, page.nextCursor != null, "a 51st row must still be reachable, not silently dropped")
        }
    }

    @Test
    fun `listForUser with an afterId past every existing row returns an empty page, not an error`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)

            val page = harness.service.listForUser(userId = 1, afterId = entry.id + 1000, limit = null)

            assertEquals(emptyList(), page.items)
            assertEquals(null, page.nextCursor)
        }
    }
}
