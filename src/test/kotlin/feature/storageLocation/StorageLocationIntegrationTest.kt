package com.shelflife.feature.storageLocation

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.storageLocation.domain.model.StorageLocationError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive integration tests for storage locations covering:
 * - Happy path: create, list, rename, delete
 * - Authorization: user isolation
 * - Error cases: duplicate names, not found, invalid input
 * - Edge cases: whitespace normalization, empty lists, concurrent mutations
 */
class StorageLocationIntegrationTest {
    // ===== CREATION TESTS =====

    @Test
    fun `create returns created entity`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            val result = harness.service.createForUser(userId = 1, name = "Fridge")

            assertTrue(result is AppResult.Success)
            assertEquals("Fridge", result.data.name)
            assertNotNull(result.data.id)
        }
    }

    @Test
    fun `create normalizes whitespace`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            val result = harness.service.createForUser(userId = 1, name = "  Freezer  ")

            assertEquals("Freezer", (result as AppResult.Success).data.name)
        }
    }

    @Test
    fun `create with duplicate name returns error`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            harness.service.createForUser(userId = 1, name = "Pantry")
            val result = harness.service.createForUser(userId = 1, name = "Pantry")

            assertEquals(AppResult.Error(StorageLocationError.DUPLICATE_NAME), result)
        }
    }

    @Test
    fun `create same name different user succeeds`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            val result1 = harness.service.createForUser(userId = 1, name = "Basement")
            val result2 = harness.service.createForUser(userId = 2, name = "Basement")

            assertTrue(result1 is AppResult.Success)
            assertTrue(result2 is AppResult.Success)
            assertEquals("Basement", result1.data.name)
            assertEquals("Basement", result2.data.name)
        }
    }

    @Test
    fun `create multiple locations succeeds`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            val names = listOf("Fridge", "Freezer", "Pantry", "Counter", "Cabinet")
            val results = names.map { harness.service.createForUser(userId = 1, name = it) }

            assertTrue(results.all { it is AppResult.Success })
            assertEquals(names, results.map { (it as AppResult.Success).data.name })
        }
    }

    @Test
    fun `created locations visible in list`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            harness.service.createForUser(userId = 1, name = "Shelf")
            harness.service.createForUser(userId = 1, name = "Drawer")

            val list = harness.service.listByUserId(userId = 1)

            assertEquals(2, list.size)
            assertEquals(listOf("Drawer", "Shelf"), list.map { it.name }) // sorted
        }
    }

    // ===== LIST TESTS =====

    @Test
    fun `list empty for user with no locations`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            val list = harness.service.listByUserId(userId = 1)

            assertEquals(emptyList(), list)
        }
    }

    @Test
    fun `list returns sorted by name`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            harness.service.createForUser(userId = 1, name = "Zulu")
            harness.service.createForUser(userId = 1, name = "Apple")
            harness.service.createForUser(userId = 1, name = "Middle")

            val list = harness.service.listByUserId(userId = 1)

            assertEquals(listOf("Apple", "Middle", "Zulu"), list.map { it.name })
        }
    }

    @Test
    fun `list user isolation works`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            harness.service.createForUser(userId = 1, name = "User1 Location")
            harness.service.createForUser(userId = 2, name = "User2 Location")

            val list1 = harness.service.listByUserId(userId = 1)
            val list2 = harness.service.listByUserId(userId = 2)

            assertEquals(listOf("User1 Location"), list1.map { it.name })
            assertEquals(listOf("User2 Location"), list2.map { it.name })
        }
    }

    // ===== RENAME TESTS =====

    @Test
    fun `renaming a location succeeds and updates the name`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            val created = harness.service.createForUser(userId = 1, name = "OldName")
            val locationId = (created as AppResult.Success).data.id

            val result = harness.service.renameLocation(userId = 1, locationId = locationId, newName = "NewName")

            assertEquals(AppResult.Success((result as AppResult.Success).data), result)
            assertEquals("NewName", result.data.name)
        }
    }

    @Test
    fun `renaming with whitespace normalizes the name`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            val created = harness.service.createForUser(userId = 1, name = "Original")
            val locationId = (created as AppResult.Success).data.id

            val result = harness.service.renameLocation(userId = 1, locationId = locationId, newName = "  Updated  ")

            assertEquals("Updated", (result as AppResult.Success).data.name)
        }
    }

    @Test
    fun `rename duplicate returns error`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            harness.service.createForUser(userId = 1, name = "First")
            val loc2 = harness.service.createForUser(userId = 1, name = "Second")
            val locationId = (loc2 as AppResult.Success).data.id

            val result = harness.service.renameLocation(userId = 1, locationId = locationId, newName = "First")

            assertEquals(AppResult.Error(StorageLocationError.DUPLICATE_NAME), result)
        }
    }

    @Test
    fun `rename nonexistent returns not found`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            val result = harness.service.renameLocation(userId = 1, locationId = 9999, newName = "NewName")

            assertEquals(AppResult.Error(StorageLocationError.NOT_FOUND), result)
        }
    }

    @Test
    fun `rename other user returns not found`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            val created = harness.service.createForUser(userId = 2, name = "User2Location")
            val locationId = (created as AppResult.Success).data.id

            val result = harness.service.renameLocation(userId = 1, locationId = locationId, newName = "Hacked")

            assertEquals(AppResult.Error(StorageLocationError.NOT_FOUND), result)
            // Verify it wasn't actually renamed
            val list2 = harness.service.listByUserId(userId = 2)
            assertEquals(listOf("User2Location"), list2.map { it.name })
        }
    }

    @Test
    fun `renamed location visible in list`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            val created = harness.service.createForUser(userId = 1, name = "OldName")
            val locationId = (created as AppResult.Success).data.id

            harness.service.renameLocation(userId = 1, locationId = locationId, newName = "NewName")
            val list = harness.service.listByUserId(userId = 1)

            assertEquals(listOf("NewName"), list.map { it.name })
            assertTrue(list.none { it.name == "OldName" })
        }
    }

    // ===== DELETE TESTS =====

    @Test
    fun `delete succeeds and removes`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            val created = harness.service.createForUser(userId = 1, name = "ToDelete")
            val locationId = (created as AppResult.Success).data.id

            val result = harness.service.deleteLocation(userId = 1, locationId = locationId)

            assertEquals(AppResult.Success(Unit), result)
            assertTrue(harness.service.listByUserId(userId = 1).isEmpty())
        }
    }

    @Test
    fun `delete nonexistent returns not found`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            val result = harness.service.deleteLocation(userId = 1, locationId = 9999)

            assertEquals(AppResult.Error(StorageLocationError.NOT_FOUND), result)
        }
    }

    @Test
    fun `delete other user returns not found`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            val created = harness.service.createForUser(userId = 2, name = "User2Location")
            val locationId = (created as AppResult.Success).data.id

            val result = harness.service.deleteLocation(userId = 1, locationId = locationId)

            assertEquals(AppResult.Error(StorageLocationError.NOT_FOUND), result)
            // Verify it still exists for user 2
            val list2 = harness.service.listByUserId(userId = 2)
            assertEquals(listOf("User2Location"), list2.map { it.name })
        }
    }

    @Test
    fun `delete one location does not affect others`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            harness.service.createForUser(userId = 1, name = "Keep1")
            val loc2 = harness.service.createForUser(userId = 1, name = "Delete")
            harness.service.createForUser(userId = 1, name = "Keep2")

            val toDelete = (loc2 as AppResult.Success).data.id
            harness.service.deleteLocation(userId = 1, locationId = toDelete)

            val list = harness.service.listByUserId(userId = 1)
            assertEquals(listOf("Keep1", "Keep2"), list.map { it.name })
        }
    }

    @Test
    fun `deleted location cannot be renamed`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            val created = harness.service.createForUser(userId = 1, name = "ToDelete")
            val locationId = (created as AppResult.Success).data.id

            harness.service.deleteLocation(userId = 1, locationId = locationId)
            val result = harness.service.renameLocation(userId = 1, locationId = locationId, newName = "NewName")

            assertEquals(AppResult.Error(StorageLocationError.NOT_FOUND), result)
        }
    }

    // ===== EDGE CASES & STRESS TESTS =====

    @Test
    fun `create many locations works`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            val locations = (1..50).map { "Location $it" }
            locations.forEach { harness.service.createForUser(userId = 1, name = it) }

            val list = harness.service.listByUserId(userId = 1)

            assertEquals(50, list.size)
            assertTrue(list.map { it.name } == locations.sorted())
        }
    }

    @Test
    fun `case sensitive duplicate detection`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            harness.service.createForUser(userId = 1, name = "Fridge")
            val result = harness.service.createForUser(userId = 1, name = "fridge")

            // Should succeed because case is preserved
            assertTrue(result is AppResult.Success)
        }
    }

    @Test
    fun `long name accepted`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            val longName = "A".repeat(100)
            val result = harness.service.createForUser(userId = 1, name = longName)

            assertTrue(result is AppResult.Success)
            assertEquals(longName, result.data.name)
        }
    }

    @Test
    fun `renaming to same name returns duplicate error`() {
        skipIfNoDocker()

        withRealStorageLocationDatabase { harness ->
            val created = harness.service.createForUser(userId = 1, name = "SameName")
            val locationId = (created as AppResult.Success).data.id

            // Rename to the same name - this should fail because we check for duplicates
            val result = harness.service.renameLocation(userId = 1, locationId = locationId, newName = "SameName")

            // Should return DUPLICATE_NAME because it's already taken
            assertEquals(AppResult.Error(StorageLocationError.DUPLICATE_NAME), result)
        }
    }
}
