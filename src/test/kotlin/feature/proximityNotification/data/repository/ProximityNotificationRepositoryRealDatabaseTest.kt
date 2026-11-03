/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.proximityNotification.data.repository

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import com.mozgobolt.feature.savedLocation.data.repository.SavedLocationRepositoryI
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the database-backed notification claim and the ownership/cascade relationships that
 * cannot be proved by [ProximityAlertServiceITest]'s fake repositories.
 */
class ProximityNotificationRepositoryRealDatabaseTest {
    private data class ClaimAttempt(
        val buyerUserId: Int,
        val savedLocationId: Int,
        val vehicleId: Int,
        val attemptedAt: Instant,
        val cooldownCutoff: Instant,
    )

    @Test
    fun `concurrent first-time claims produce exactly one notification winner`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = ProximityNotificationRepositoryI()
            val (savedLocationId, vehicleId) =
                tx.transactional {
                    val company =
                        CompanyRepositoryI().create(
                            name = "FamilyFrost",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    val vehicle =
                        VehicleRepositoryI().create(
                            companyId = company.id,
                            label = "Proximity Truck",
                            licensePlate = "PROX-1",
                        )!!
                    val savedLocation =
                        SavedLocationRepositoryI().create(
                            userId = 1,
                            label = "Home",
                            latitude = 47.4979,
                            longitude = 19.0402,
                            radiusKm = 1.0,
                        )
                    savedLocation.id to vehicle.id
                }

            val now = Instant.parse("2026-01-01T00:00:00Z")
            val outcomes =
                raceClaims(
                    tx = tx,
                    repository = repository,
                    attempt =
                        ClaimAttempt(
                            buyerUserId = 1,
                            savedLocationId = savedLocationId,
                            vehicleId = vehicleId,
                            attemptedAt = now,
                            cooldownCutoff = now.minusSeconds(30 * 60),
                        ),
                )

            assertEquals(
                1,
                outcomes.count { it },
                "exactly one concurrent first-time claim should be allowed: $outcomes",
            )

            val survivingRecord =
                tx.transactional { repository.find(1, savedLocationId, vehicleId) }
            assertNotNull(survivingRecord, "the winning claim must persist its triple")
            assertEquals(now.epochSecond, survivingRecord.lastAttemptedAt.epochSecond)
        }
    }

    @Test
    fun `find, recordEntryAttempted, and markOutside round-trip correctly against a real database`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = ProximityNotificationRepositoryI()
            val (savedLocationId, vehicleId) =
                tx.transactional {
                    val company =
                        CompanyRepositoryI().create(
                            name = "FamilyFrost",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    val vehicle =
                        VehicleRepositoryI().create(
                            companyId = company.id,
                            label = "Proximity Truck",
                            licensePlate = "PROX-2",
                        )!!
                    val savedLocation =
                        SavedLocationRepositoryI().create(
                            userId = 1,
                            label = "Home",
                            latitude = 47.4979,
                            longitude = 19.0402,
                            radiusKm = 1.0,
                        )
                    savedLocation.id to vehicle.id
                }

            assertTrue(tx.transactional { repository.find(1, savedLocationId, vehicleId) } == null)

            val attemptedAt = Instant.now()
            val afterEntry =
                tx.transactional {
                    repository.recordEntryAttempted(1, savedLocationId, vehicleId, attemptedAt)
                    repository.find(1, savedLocationId, vehicleId)
                }
            assertNotNull(afterEntry)
            assertTrue(afterEntry.currentlyInside)
            assertEquals(attemptedAt.epochSecond, afterEntry.lastAttemptedAt.epochSecond)

            val afterExit =
                tx.transactional {
                    repository.markOutside(1, savedLocationId, vehicleId)
                    repository.find(1, savedLocationId, vehicleId)
                }
            assertNotNull(afterExit)
            assertTrue(!afterExit.currentlyInside)
            assertEquals(
                attemptedAt.epochSecond,
                afterExit.lastAttemptedAt.epochSecond,
                "leaving must not touch lastAttemptedAt",
            )
        }
    }

    @Test
    fun `concurrent claims after cooldown allow only one reminder`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = ProximityNotificationRepositoryI()
            val (savedLocationId, vehicleId) =
                tx.transactional {
                    val company =
                        CompanyRepositoryI().create(
                            name = "Cooldown Company",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    val vehicle =
                        VehicleRepositoryI().create(
                            companyId = company.id,
                            label = "Cooldown Truck",
                            licensePlate = "PROX-COOLDOWN",
                        )!!
                    val savedLocation =
                        SavedLocationRepositoryI().create(
                            userId = 1,
                            label = "Home",
                            latitude = 47.4979,
                            longitude = 19.0402,
                            radiusKm = 1.0,
                        )
                    savedLocation.id to vehicle.id
                }
            val lastAttemptedAt = Instant.parse("2026-01-01T00:00:00Z")
            val now = lastAttemptedAt.plusSeconds(30 * 60)
            tx.transactional {
                repository.recordEntryAttempted(1, savedLocationId, vehicleId, lastAttemptedAt)
            }

            val outcomes =
                raceClaims(
                    tx = tx,
                    repository = repository,
                    attempt =
                        ClaimAttempt(
                            buyerUserId = 1,
                            savedLocationId = savedLocationId,
                            vehicleId = vehicleId,
                            attemptedAt = now,
                            cooldownCutoff = lastAttemptedAt,
                        ),
                )

            assertEquals(
                1,
                outcomes.count { it },
                "only one concurrent caller may claim the elapsed cooldown: $outcomes",
            )
            assertEquals(
                now.epochSecond,
                tx.transactional {
                    repository.find(1, savedLocationId, vehicleId)?.lastAttemptedAt?.epochSecond
                },
            )
        }
    }

    @Test
    fun `entry claim respects the exact cooldown cutoff and immediate re-entry`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = ProximityNotificationRepositoryI()
            val (savedLocationId, vehicleId) =
                tx.transactional {
                    val company =
                        CompanyRepositoryI().create(
                            name = "Boundary Company",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    val vehicle =
                        VehicleRepositoryI().create(
                            companyId = company.id,
                            label = "Boundary Truck",
                            licensePlate = "PROX-BOUNDARY",
                        )!!
                    val savedLocation =
                        SavedLocationRepositoryI().create(
                            userId = 1,
                            label = "Home",
                            latitude = 47.4979,
                            longitude = 19.0402,
                            radiusKm = 1.0,
                        )
                    savedLocation.id to vehicle.id
                }
            val initial = Instant.parse("2026-01-01T00:00:00Z")
            tx.transactional { repository.recordEntryAttempted(1, savedLocationId, vehicleId, initial) }

            assertEquals(
                false,
                tx.transactional {
                    repository.claimEntryNotification(
                        1,
                        savedLocationId,
                        vehicleId,
                        initial.plusSeconds(1),
                        initial.minusMillis(1),
                    )
                },
                "a last notification newer than the cutoff must suppress a reminder",
            )
            assertEquals(
                true,
                tx.transactional {
                    repository.claimEntryNotification(
                        1,
                        savedLocationId,
                        vehicleId,
                        initial.plusSeconds(2),
                        initial,
                    )
                },
                "equality with the cutoff means the cooldown has elapsed",
            )

            tx.transactional { repository.markOutside(1, savedLocationId, vehicleId) }
            assertEquals(
                true,
                tx.transactional {
                    repository.claimEntryNotification(
                        1,
                        savedLocationId,
                        vehicleId,
                        initial.plusSeconds(3),
                        initial.plusSeconds(2),
                    )
                },
                "re-entry must claim even when the cooldown has not elapsed",
            )
        }
    }

    @Test
    fun `a proximity record requires its buyer to own the referenced saved location`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = ProximityNotificationRepositoryI()
            val (savedLocationId, vehicleId) =
                tx.transactional {
                    val company =
                        CompanyRepositoryI().create(
                            name = "Ownership Company",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    val vehicle =
                        VehicleRepositoryI().create(
                            companyId = company.id,
                            label = "Ownership Truck",
                            licensePlate = "PROX-OWNER",
                        )!!
                    val savedLocation =
                        SavedLocationRepositoryI().create(
                            userId = 1,
                            label = "Buyer One Home",
                            latitude = 47.4979,
                            longitude = 19.0402,
                            radiusKm = 1.0,
                        )
                    savedLocation.id to vehicle.id
                }

            val invalidReferences =
                listOf(
                    Triple(2, savedLocationId, vehicleId),
                    Triple(Int.MAX_VALUE, savedLocationId, vehicleId),
                    Triple(1, Int.MAX_VALUE, vehicleId),
                    Triple(1, savedLocationId, Int.MAX_VALUE),
                )
            invalidReferences.forEach { (buyerUserId, locationId, invalidVehicleId) ->
                val failure =
                    assertFails {
                        tx.transactional {
                            repository.recordEntryAttempted(
                                buyerUserId = buyerUserId,
                                savedLocationId = locationId,
                                vehicleId = invalidVehicleId,
                                attemptedAt = Instant.parse("2026-01-01T00:00:00Z"),
                            )
                        }
                    }

                assertSqlState(failure, "23503")
                assertNull(tx.transactional { repository.find(buyerUserId, locationId, invalidVehicleId) })
            }

            val valid =
                tx.transactional {
                    repository.recordEntryAttempted(
                        buyerUserId = 1,
                        savedLocationId = savedLocationId,
                        vehicleId = vehicleId,
                        attemptedAt = Instant.parse("2026-01-01T00:00:01Z"),
                    )
                }
            assertEquals(1, valid.buyerUserId)
            assertEquals(savedLocationId, valid.savedLocationId)
        }
    }

    @Test
    fun `deleting a saved location cascades only its proximity state`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = ProximityNotificationRepositoryI()
            val savedLocationRepository = SavedLocationRepositoryI()
            val (deletedLocationId, retainedLocationId, vehicleId) =
                tx.transactional {
                    val company =
                        CompanyRepositoryI().create(
                            name = "Cascade Company",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    val vehicle =
                        VehicleRepositoryI().create(
                            companyId = company.id,
                            label = "Cascade Truck",
                            licensePlate = "PROX-CASCADE",
                        )!!
                    val deletedLocation =
                        savedLocationRepository.create(
                            userId = 1,
                            label = "Delete",
                            latitude = 47.4979,
                            longitude = 19.0402,
                            radiusKm = 1.0,
                        )
                    val retainedLocation =
                        savedLocationRepository.create(
                            userId = 1,
                            label = "Retain",
                            latitude = 47.5,
                            longitude = 19.1,
                            radiusKm = 1.0,
                        )
                    val attemptedAt = Instant.parse("2026-01-01T00:00:00Z")
                    repository.recordEntryAttempted(1, deletedLocation.id, vehicle.id, attemptedAt)
                    repository.recordEntryAttempted(1, retainedLocation.id, vehicle.id, attemptedAt)
                    Triple(deletedLocation.id, retainedLocation.id, vehicle.id)
                }

            tx.transactional { savedLocationRepository.delete(deletedLocationId) }

            assertNull(tx.transactional { repository.find(1, deletedLocationId, vehicleId) })
            assertNotNull(tx.transactional { repository.find(1, retainedLocationId, vehicleId) })
        }
    }

    private fun assertSqlState(
        failure: Throwable,
        sqlState: String,
    ) {
        assertTrue(
            generateSequence<Throwable>(failure) { it.cause }
                .filterIsInstance<SQLException>()
                .any { it.sqlState == sqlState },
            "expected PostgreSQL SQLSTATE $sqlState",
        )
    }

    private suspend fun raceClaims(
        tx: TransactionalRunner,
        repository: ProximityNotificationRepositoryI,
        attempt: ClaimAttempt,
    ): List<Boolean> =
        coroutineScope {
            val ready = AtomicInteger()
            val start = CompletableDeferred<Unit>()
            List(2) {
                async {
                    if (ready.incrementAndGet() == 2) start.complete(Unit)
                    withTimeout(5_000) { start.await() }
                    tx.transactional {
                        repository.claimEntryNotification(
                            buyerUserId = attempt.buyerUserId,
                            savedLocationId = attempt.savedLocationId,
                            vehicleId = attempt.vehicleId,
                            attemptedAt = attempt.attemptedAt,
                            cooldownCutoff = attempt.cooldownCutoff,
                        )
                    }
                }
            }.awaitAll()
        }
}
