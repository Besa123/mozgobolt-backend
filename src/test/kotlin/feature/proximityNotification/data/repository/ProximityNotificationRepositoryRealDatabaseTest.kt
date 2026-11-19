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
import com.mozgobolt.feature.proximityNotification.domain.model.ProximityNotificationCandidate
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
                    repository.markOutside(vehicleId, listOf(savedLocationId))
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
    fun `markOutside updates only this vehicle's inside rows for the given locations in one statement`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = ProximityNotificationRepositoryI()
            val fixture =
                tx.transactional {
                    val company =
                        CompanyRepositoryI().create(
                            name = "Batch Company",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    val vehicles = VehicleRepositoryI()
                    val savedLocations = SavedLocationRepositoryI()

                    suspend fun savedLocationId(label: String) =
                        savedLocations.create(1, label, 47.4979, 19.0402, 1.0).id
                    BatchFixture(
                        vehicleId = vehicles.create(company.id, "Batch Truck", "PROX-BATCH-1")!!.id,
                        otherVehicleId = vehicles.create(company.id, "Other Truck", "PROX-BATCH-2")!!.id,
                        leaving = listOf(savedLocationId("Home"), savedLocationId("Work")),
                        staying = savedLocationId("Park"),
                    )
                }
            val attemptedAt = Instant.now()
            tx.transactional {
                (fixture.leaving + fixture.staying).forEach {
                    repository.recordEntryAttempted(1, it, fixture.vehicleId, attemptedAt)
                }
                repository.recordEntryAttempted(1, fixture.leaving.first(), fixture.otherVehicleId, attemptedAt)
            }

            tx.transactional {
                repository.markOutside(fixture.vehicleId, emptyList())
                repository.markOutside(fixture.vehicleId, fixture.leaving)
            }

            suspend fun isInside(
                savedLocationId: Int,
                vehicleId: Int,
            ) = tx.transactional { repository.find(1, savedLocationId, vehicleId) }!!.currentlyInside
            assertTrue(
                fixture.leaving.none { isInside(it, fixture.vehicleId) },
                "requested locations are marked outside",
            )
            assertTrue(isInside(fixture.staying, fixture.vehicleId), "an unrequested location is untouched")
            assertTrue(isInside(fixture.leaving.first(), fixture.otherVehicleId), "another vehicle is untouched")
            assertEquals(
                attemptedAt.epochSecond,
                tx
                    .transactional {
                        repository.find(
                            1,
                            fixture.leaving.first(),
                            fixture.vehicleId,
                        )
                    }!!
                    .lastAttemptedAt.epochSecond,
                "leaving must not touch lastAttemptedAt",
            )
        }
    }

    private data class BatchFixture(
        val vehicleId: Int,
        val otherVehicleId: Int,
        val leaving: List<Int>,
        val staying: Int,
    )

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

            tx.transactional { repository.markOutside(vehicleId, listOf(savedLocationId)) }
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
    fun `batch claims preserve cooldown exit and re-entry semantics`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = ProximityNotificationRepositoryI()
            val (vehicleId, candidates) =
                tx.transactional {
                    val company =
                        CompanyRepositoryI().create(
                            name = "Batch Claim Company",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    val vehicle =
                        VehicleRepositoryI().create(
                            companyId = company.id,
                            label = "Batch Claim Truck",
                            licensePlate = "PROX-BATCH-CLAIM",
                        )!!
                    val savedLocationRepository = SavedLocationRepositoryI()
                    val firstLocation =
                        savedLocationRepository.create(
                            userId = 1,
                            label = "Home",
                            latitude = 47.4979,
                            longitude = 19.0402,
                            radiusKm = 1.0,
                        )
                    val secondLocation =
                        savedLocationRepository.create(
                            userId = 2,
                            label = "Work",
                            latitude = 47.5,
                            longitude = 19.05,
                            radiusKm = 1.0,
                        )
                    vehicle.id to
                        listOf(
                            ProximityNotificationCandidate(1, firstLocation.id),
                            ProximityNotificationCandidate(2, secondLocation.id),
                        )
                }
            val locationIds = candidates.map(ProximityNotificationCandidate::savedLocationId).toSet()
            val initial = Instant.parse("2026-01-01T00:00:00Z")
            val initialCutoff = initial.minusSeconds(30 * 60)

            assertEquals(
                locationIds,
                tx.transactional {
                    repository.claimEntryNotifications(vehicleId, candidates, initial, initialCutoff)
                },
            )
            val suppressedLocationIds =
                tx.transactional {
                    repository.claimEntryNotifications(
                        vehicleId,
                        candidates,
                        initial.plusSeconds(1),
                        initialCutoff,
                    )
                }
            assertTrue(suppressedLocationIds.isEmpty())

            val reenteredLocationId = candidates.first().savedLocationId
            tx.transactional { repository.markOutside(vehicleId, listOf(reenteredLocationId)) }
            assertEquals(
                setOf(reenteredLocationId),
                tx.transactional {
                    repository.claimEntryNotifications(
                        vehicleId,
                        candidates,
                        initial.plusSeconds(2),
                        initialCutoff,
                    )
                },
            )

            val cooldownBoundary = initial.plusSeconds(30 * 60 + 2)
            assertEquals(
                locationIds,
                tx.transactional {
                    repository.claimEntryNotifications(
                        vehicleId,
                        candidates,
                        cooldownBoundary,
                        cooldownBoundary.minusSeconds(30 * 60),
                    )
                },
            )
        }
    }

    @Test
    fun `concurrent batch claims produce one winner for each candidate`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = ProximityNotificationRepositoryI()
            val (vehicleId, candidates) =
                tx.transactional {
                    val company =
                        CompanyRepositoryI().create(
                            name = "Concurrent Batch Company",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    val vehicle =
                        VehicleRepositoryI().create(
                            companyId = company.id,
                            label = "Concurrent Batch Truck",
                            licensePlate = "PROX-BATCH-RACE",
                        )!!
                    val firstLocation =
                        SavedLocationRepositoryI().create(1, "Home", 47.4979, 19.0402, 1.0)
                    val secondLocation =
                        SavedLocationRepositoryI().create(2, "Work", 47.5, 19.05, 1.0)
                    vehicle.id to
                        listOf(
                            ProximityNotificationCandidate(1, firstLocation.id),
                            ProximityNotificationCandidate(2, secondLocation.id),
                        )
                }
            val expected = candidates.map(ProximityNotificationCandidate::savedLocationId).toSet()
            val attemptedAt = Instant.parse("2026-01-01T00:00:00Z")
            val outcomes =
                raceBatchClaims(
                    tx = tx,
                    repository = repository,
                    vehicleId = vehicleId,
                    candidates = candidates,
                    attemptedAt = attemptedAt,
                    cooldownCutoff = attemptedAt.minusSeconds(30 * 60),
                )

            assertEquals(1, outcomes.count { it == expected }, "one batch should claim all candidates")
            assertEquals(1, outcomes.count { it.isEmpty() }, "the competing batch must claim none")
        }
    }

    @Test
    fun `batch claim rolls back earlier chunks when a later chunk fails`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = ProximityNotificationRepositoryI()
            val (vehicleId, candidates) =
                tx.transactional {
                    val company =
                        CompanyRepositoryI().create(
                            name = "Rollback Batch Company",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    val vehicle =
                        VehicleRepositoryI().create(
                            companyId = company.id,
                            label = "Rollback Batch Truck",
                            licensePlate = "PROX-BATCH-ROLLBACK",
                        )!!
                    val savedLocationRepository = SavedLocationRepositoryI()
                    val locations =
                        // Keep one current 500-candidate chunk valid before the invalid second chunk.
                        List(500) { index ->
                            savedLocationRepository.create(
                                userId = 1,
                                label = "Rollback $index",
                                latitude = 47.4979,
                                longitude = 19.0402,
                                radiusKm = 1.0,
                            )
                        }
                    vehicle.id to locations.map { ProximityNotificationCandidate(1, it.id) }
                }
            val attemptedAt = Instant.parse("2026-01-01T00:00:00Z")
            val invalidCandidate =
                ProximityNotificationCandidate(
                    buyerUserId = 1,
                    savedLocationId = Int.MAX_VALUE,
                )

            assertFails("a foreign-key violation in a later chunk must fail the claim transaction") {
                tx.transactional {
                    repository.claimEntryNotifications(
                        vehicleId = vehicleId,
                        candidates = candidates + invalidCandidate,
                        attemptedAt = attemptedAt,
                        cooldownCutoff = attemptedAt.minusSeconds(30 * 60),
                    )
                }
            }
            assertNull(
                tx.transactional {
                    repository.find(1, candidates.first().savedLocationId, vehicleId)
                },
                "rows inserted by earlier chunks must roll back with the failed batch",
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

    private suspend fun raceBatchClaims(
        tx: TransactionalRunner,
        repository: ProximityNotificationRepositoryI,
        vehicleId: Int,
        candidates: List<ProximityNotificationCandidate>,
        attemptedAt: Instant,
        cooldownCutoff: Instant,
    ): List<Set<Int>> =
        coroutineScope {
            val ready = AtomicInteger()
            val start = CompletableDeferred<Unit>()
            val attempts =
                listOf(candidates, candidates.reversed()).map { candidateBatch ->
                    async {
                        if (ready.incrementAndGet() == 2) start.complete(Unit)
                        withTimeout(5_000) { start.await() }
                        tx.transactional {
                            repository.claimEntryNotifications(
                                vehicleId = vehicleId,
                                candidates = candidateBatch,
                                attemptedAt = attemptedAt,
                                cooldownCutoff = cooldownCutoff,
                            )
                        }
                    }
                }
            attempts.awaitAll()
        }
}
