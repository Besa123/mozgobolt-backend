/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.savedLocation.service

import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import com.mozgobolt.feature.proximityNotification.data.repository.ProximityNotificationRepositoryI
import com.mozgobolt.feature.savedLocation.data.repository.SavedLocationRepositoryI
import com.mozgobolt.feature.savedLocation.domain.SavedLocationRepository
import com.mozgobolt.feature.savedLocation.domain.model.SavedLocationError
import com.mozgobolt.feature.savedLocation.domain.model.UserSavedLocation
import com.mozgobolt.feature.sync.data.repository.SyncRepositoryI
import com.mozgobolt.feature.sync.domain.SyncService
import com.mozgobolt.feature.sync.domain.model.SyncChange
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.sync.domain.model.SyncPage
import com.mozgobolt.feature.sync.service.SyncServiceI
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedLocationServiceRealDatabaseTest {
    @Test
    fun `concurrent deletion requests only record one sync tombstone`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = SavedLocationRepositoryI()
            val savedLocation =
                tx.transactional {
                    repository.create(1, "Concurrent delete", 47.4979, 19.0402, 2.5)
                }
            val reads = AtomicInteger()
            val bothReadsCompleted = CompletableDeferred<Unit>()
            val coordinatedRepository =
                object : SavedLocationRepository by repository {
                    override suspend fun findById(id: Int): UserSavedLocation? {
                        val existing = repository.findById(id)
                        if (id == savedLocation.id && existing != null) {
                            if (reads.incrementAndGet() == 2) bothReadsCompleted.complete(Unit)
                            withTimeout(5_000) { bothReadsCompleted.await() }
                        }
                        return existing
                    }
                }
            val syncRepository = SyncRepositoryI()
            val service =
                SavedLocationServiceI(
                    savedLocationRepository = coordinatedRepository,
                    syncService = SyncServiceI(syncRepository, tx),
                    tx = tx,
                )

            val results =
                coroutineScope {
                    listOf(
                        async(Dispatchers.IO) { service.deleteSavedLocation(1, savedLocation.id) },
                        async(Dispatchers.IO) { service.deleteSavedLocation(1, savedLocation.id) },
                    ).awaitAll()
                }

            results.forEach { result ->
                result.fold(
                    onSuccess = {},
                    onError = { error("both concurrent delete requests should succeed: $it") },
                )
            }
            assertNull(tx.transactional { repository.findById(savedLocation.id) })
            val deleteEvents =
                tx.transactional {
                    syncRepository
                        .findSince(1, 0, 10)
                        .events
                        .filter {
                            it.entityType == SyncEntityType.USER_SAVED_LOCATION &&
                                it.entityId == savedLocation.id &&
                                it.operation == SyncOperation.DELETE
                        }
                }
            assertEquals(1, deleteEvents.size)
        }
    }

    @Test
    fun `owner deletion cascades proximity state and records a scoped sync tombstone`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val savedLocationRepository = SavedLocationRepositoryI()
            val proximityNotificationRepository = ProximityNotificationRepositoryI()
            val syncRepository = SyncRepositoryI()
            val service =
                SavedLocationServiceI(
                    savedLocationRepository = savedLocationRepository,
                    syncService = SyncServiceI(syncRepository, tx),
                    tx = tx,
                )
            val savedLocation =
                service.createSavedLocation(
                    userId = 1,
                    label = "  Home  ",
                    latitude = 47.4979,
                    longitude = 19.0402,
                    radiusKm = 2.5,
                )
            val vehicleId =
                tx.transactional {
                    val company =
                        CompanyRepositoryI().create(
                            name = "Saved Location Company",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    VehicleRepositoryI()
                        .create(company.id, "Saved Location Truck", "SAVED-LOCATION-PLATE")!!
                        .id
                }
            tx.transactional {
                proximityNotificationRepository.recordEntryAttempted(
                    buyerUserId = 1,
                    savedLocationId = savedLocation.id,
                    vehicleId = vehicleId,
                    attemptedAt = Instant.parse("2026-01-01T00:00:00Z"),
                )
            }

            val creationEvents = tx.transactional { syncRepository.findSince(1, 0, 10).events }
            assertEquals(
                listOf(SyncOperation.UPSERT),
                creationEvents
                    .filter { it.entityType == SyncEntityType.USER_SAVED_LOCATION }
                    .map { it.operation },
            )
            assertEquals(savedLocation.id, creationEvents.single().entityId)

            val unauthorizedDelete =
                service.deleteSavedLocation(
                    userId = 2,
                    savedLocationId = savedLocation.id,
                )
            unauthorizedDelete.fold(
                onSuccess = { error("another user must not delete this location") },
                onError = { assertEquals(SavedLocationError.NOT_FOUND, it) },
            )
            assertNotNull(tx.transactional { savedLocationRepository.findById(savedLocation.id) })
            assertNotNull(
                tx.transactional {
                    proximityNotificationRepository.find(1, savedLocation.id, vehicleId)
                },
            )
            assertEquals(creationEvents, tx.transactional { syncRepository.findSince(1, 0, 10).events })

            val deleteResult = service.deleteSavedLocation(userId = 1, savedLocationId = savedLocation.id)
            deleteResult.fold(
                onSuccess = {},
                onError = { error("owner delete should succeed: $it") },
            )

            assertNull(tx.transactional { savedLocationRepository.findById(savedLocation.id) })
            assertNull(
                tx.transactional {
                    proximityNotificationRepository.find(1, savedLocation.id, vehicleId)
                },
            )
            val allSyncEvents = tx.transactional { syncRepository.findSince(1, 0, 10).events }
            val savedLocationEvents =
                allSyncEvents.filter { it.entityType == SyncEntityType.USER_SAVED_LOCATION }
            assertEquals(
                listOf(SyncOperation.UPSERT, SyncOperation.DELETE),
                savedLocationEvents.map { it.operation },
            )
            assertTrue(savedLocationEvents.all { it.entityId == savedLocation.id })
        }
    }

    @Test
    fun `sync failure rolls back saved-location creation and deletion`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = SavedLocationRepositoryI()
            val service =
                SavedLocationServiceI(
                    savedLocationRepository = repository,
                    syncService = FailingSyncService,
                    tx = tx,
                )

            assertFails {
                service.createSavedLocation(
                    userId = 1,
                    label = "Rollback create",
                    latitude = 47.4979,
                    longitude = 19.0402,
                    radiusKm = 2.5,
                )
            }
            assertTrue(tx.transactional { repository.findAllForUser(1) }.isEmpty())

            val existing =
                tx.transactional {
                    repository.create(1, "Rollback delete", 47.4979, 19.0402, 2.5)
                }
            assertFails {
                service.deleteSavedLocation(userId = 1, savedLocationId = existing.id)
            }
            assertNotNull(tx.transactional { repository.findById(existing.id) })
        }
    }

    private object FailingSyncService : SyncService {
        override suspend fun recordChange(
            userId: Int,
            entityType: SyncEntityType,
            entityId: Int,
            operation: SyncOperation,
            originDeviceId: String?,
        ) {
            error("synthetic sync failure")
        }

        override suspend fun recordChanges(changes: List<SyncChange>) {
            error("synthetic sync failure")
        }

        override suspend fun changesSince(
            userId: Int,
            cursor: Long,
            limit: Int?,
        ): SyncPage = error("not exercised")
    }
}
