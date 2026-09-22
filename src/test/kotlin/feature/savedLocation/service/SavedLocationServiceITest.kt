package com.mozgobolt.feature.savedLocation.service

import com.mozgobolt.feature.savedLocation.domain.SavedLocationRepository
import com.mozgobolt.feature.savedLocation.domain.model.SavedLocationError
import com.mozgobolt.feature.savedLocation.domain.model.UserSavedLocation
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.sync.routing.FakeSyncService
import com.mozgobolt.feature.user.service.NoopTransactionalRunner
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class SavedLocationServiceITest {
    private class FakeSavedLocationRepository : SavedLocationRepository {
        private val locationsById = mutableMapOf<Int, UserSavedLocation>()
        private var nextId = 1

        override suspend fun create(
            userId: Int,
            label: String,
            latitude: Double,
            longitude: Double,
            radiusKm: Double,
        ): UserSavedLocation {
            val location =
                UserSavedLocation(
                    id = nextId++,
                    userId = userId,
                    label = label,
                    latitude = latitude,
                    longitude = longitude,
                    radiusKm = radiusKm,
                    createdAt = Instant.now(),
                )
            locationsById[location.id] = location
            return location
        }

        override suspend fun findById(id: Int): UserSavedLocation? = locationsById[id]

        override suspend fun findAllForUser(userId: Int): List<UserSavedLocation> =
            locationsById.values.filter { it.userId == userId }

        override suspend fun delete(id: Int) {
            locationsById.remove(id)
        }

        override suspend fun findAllForUsers(userIds: Collection<Int>): List<UserSavedLocation> =
            locationsById.values.filter { it.userId in userIds }
    }

    private class Fixture {
        val repository = FakeSavedLocationRepository()
        val syncService = FakeSyncService()
        val service =
            SavedLocationServiceI(
                savedLocationRepository = repository,
                syncService = syncService,
                tx = NoopTransactionalRunner(),
            )
    }

    @Test
    fun `creating a saved location succeeds and records a sync event`() {
        runBlocking {
            val fx = Fixture()

            val created = fx.service.createSavedLocation(10, "Home", 47.4979, 19.0402, 2.5)

            assertEquals("Home", created.label)
            assertEquals(listOf(created.id), fx.service.listSavedLocations(10).map { it.id })
            assertTrue(fx.syncService.recorded.any { it.entityType == SyncEntityType.USER_SAVED_LOCATION })
        }
    }

    @Test
    fun `the label is trimmed before being stored`() {
        runBlocking {
            val fx = Fixture()

            val created = fx.service.createSavedLocation(10, "  Home  ", 47.4979, 19.0402, 2.5)

            assertEquals("Home", created.label)
        }
    }

    @Test
    fun `saved locations never leak across users`() {
        runBlocking {
            val fx = Fixture()
            fx.service.createSavedLocation(10, "Home", 47.4979, 19.0402, 2.5)
            fx.service.createSavedLocation(20, "Work", 47.5, 19.1, 1.0)

            assertEquals(listOf("Home"), fx.service.listSavedLocations(10).map { it.label })
            assertEquals(listOf("Work"), fx.service.listSavedLocations(20).map { it.label })
        }
    }

    @Test
    fun `deleting your own saved location succeeds and records a sync event`() {
        runBlocking {
            val fx = Fixture()
            val created = fx.service.createSavedLocation(10, "Home", 47.4979, 19.0402, 2.5)

            val result = fx.service.deleteSavedLocation(userId = 10, savedLocationId = created.id)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertTrue(fx.service.listSavedLocations(10).isEmpty())
            assertTrue(fx.syncService.recorded.any { it.operation == SyncOperation.DELETE })
        }
    }

    @Test
    fun `deleting a saved location that doesn't exist is rejected`() {
        runBlocking {
            val fx = Fixture()

            val result = fx.service.deleteSavedLocation(userId = 10, savedLocationId = 999)

            result.fold(onSuccess = { fail("expected NOT_FOUND but got success") }, onError = {
                assertEquals(SavedLocationError.NOT_FOUND, it)
            })
        }
    }

    @Test
    fun `deleting another user's saved location is rejected as not found, not forbidden`() {
        runBlocking {
            val fx = Fixture()
            val created = fx.service.createSavedLocation(10, "Home", 47.4979, 19.0402, 2.5)

            val result = fx.service.deleteSavedLocation(userId = 20, savedLocationId = created.id)

            result.fold(onSuccess = { fail("expected NOT_FOUND but got success") }, onError = {
                assertEquals(SavedLocationError.NOT_FOUND, it)
            })
            assertEquals(1, fx.service.listSavedLocations(10).size, "the victim's location must survive untouched")
        }
    }
}
