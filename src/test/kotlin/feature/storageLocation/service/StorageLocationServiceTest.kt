package com.shelflife.feature.storageLocation.service

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.storageLocation.domain.model.StorageLocationError
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class StorageLocationServiceTest {
    @Test
    fun `creating a location succeeds and trims the name`() {
        runBlocking {
            val harness = newHarness()

            val result = harness.service.createForUser(userId = 1, name = "  Fridge  ")

            result.fold(
                onSuccess = { assertEquals("Fridge", it.name) },
                onError = { fail("expected success but got $it") },
            )
        }
    }

    @Test
    fun `creating a location with a name already used by the same user is rejected`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seed(userId = 1, name = "Fridge")

            val result = harness.service.createForUser(userId = 1, name = "Fridge")

            assertEquals(AppResult.Error(StorageLocationError.DUPLICATE_NAME), result)
        }
    }

    @Test
    fun `the same name is not a collision across two different users`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seed(userId = 1, name = "Fridge")

            val result = harness.service.createForUser(userId = 2, name = "Fridge")

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
        }
    }

    @Test
    fun `listing returns only the caller's own locations, sorted by name`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seed(userId = 1, name = "Zulu")
            harness.repository.seed(userId = 1, name = "Apple")
            harness.repository.seed(userId = 2, name = "Other User's Location")

            val results = harness.service.listByUserId(userId = 1)

            assertEquals(listOf("Apple", "Zulu"), results.map { it.name })
        }
    }

    @Test
    fun `renaming a location the caller owns succeeds and trims the name`() {
        runBlocking {
            val harness = newHarness()
            val location = harness.repository.seed(userId = 1, name = "OldName")

            val result = harness.service.renameLocation(userId = 1, locationId = location.id, newName = "  NewName  ")

            result.fold(
                onSuccess = { assertEquals("NewName", it.name) },
                onError = { fail("expected success but got $it") },
            )
        }
    }

    @Test
    fun `renaming a location owned by another user is not found`() {
        runBlocking {
            val harness = newHarness()
            val location = harness.repository.seed(userId = 2, name = "OldName")

            val result = harness.service.renameLocation(userId = 1, locationId = location.id, newName = "NewName")

            assertEquals(AppResult.Error(StorageLocationError.NOT_FOUND), result)
        }
    }

    @Test
    fun `renaming a nonexistent location is not found`() {
        runBlocking {
            val harness = newHarness()

            val result = harness.service.renameLocation(userId = 1, locationId = 999, newName = "NewName")

            assertEquals(AppResult.Error(StorageLocationError.NOT_FOUND), result)
        }
    }

    @Test
    fun `renaming to a name that collides with another of the caller's locations is rejected`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seed(userId = 1, name = "Fridge")
            val toRename = harness.repository.seed(userId = 1, name = "Old")

            val result = harness.service.renameLocation(userId = 1, locationId = toRename.id, newName = "Fridge")

            assertEquals(AppResult.Error(StorageLocationError.DUPLICATE_NAME), result)
        }
    }

    @Test
    fun `renaming a location to its own current name is a no-op success`() {
        runBlocking {
            val harness = newHarness()
            val location = harness.repository.seed(userId = 1, name = "Fridge")

            val result = harness.service.renameLocation(userId = 1, locationId = location.id, newName = "Fridge")

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
        }
    }

    @Test
    fun `deleting a location the caller owns succeeds`() {
        runBlocking {
            val harness = newHarness()
            val location = harness.repository.seed(userId = 1, name = "ToDelete")

            val result = harness.service.deleteLocation(userId = 1, locationId = location.id)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
        }
    }

    @Test
    fun `deleting a location owned by another user is not found`() {
        runBlocking {
            val harness = newHarness()
            val location = harness.repository.seed(userId = 2, name = "NotYours")

            val result = harness.service.deleteLocation(userId = 1, locationId = location.id)

            assertEquals(AppResult.Error(StorageLocationError.NOT_FOUND), result)
        }
    }

    @Test
    fun `deleting a nonexistent location is not found`() {
        runBlocking {
            val harness = newHarness()

            val result = harness.service.deleteLocation(userId = 1, locationId = 999)

            assertEquals(AppResult.Error(StorageLocationError.NOT_FOUND), result)
        }
    }
}
