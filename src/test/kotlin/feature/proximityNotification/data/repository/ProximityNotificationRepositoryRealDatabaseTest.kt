package com.mozgobolt.feature.proximityNotification.data.repository

import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.savedLocation.data.repository.SavedLocationRepositoryI
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves the real `UNIQUE(buyer_user_id, saved_location_id, vehicle_id)` index on
 * `proximity_notifications` actually fires under genuine Postgres concurrency — the class of bug
 * invisible to [ProximityAlertServiceITest]'s fakes, only caught by a real DB. Unlike
 * `CompanyFavoriteRepositoryI.addIfAbsent` (which uses `insertIgnore` and so never raises the
 * violation), [ProximityNotificationRepositoryI.recordEntryNotified] is a plain find-then-write —
 * this test documents that the loser of a genuine race gets a constraint-violation exception, not
 * a silent no-op, exactly as [ProximityNotificationRepositoryI]'s kdoc says is accepted.
 */
class ProximityNotificationRepositoryRealDatabaseTest {
    @Test
    fun `two concurrent first-time entries for the same triple leave exactly one row, the loser may throw`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = ProximityNotificationRepositoryI()
            val (savedLocationId, vehicleId) =
                tx.transactional {
                    val company = CompanyRepositoryI().create(name = "FamilyFrost", inviteCode = "prox-race-code")
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

            val outcomes =
                coroutineScope {
                    val first =
                        async {
                            runCatching {
                                tx.transactional {
                                    repository.recordEntryNotified(1, savedLocationId, vehicleId, Instant.now())
                                }
                            }
                        }
                    val second =
                        async {
                            runCatching {
                                tx.transactional {
                                    repository.recordEntryNotified(1, savedLocationId, vehicleId, Instant.now())
                                }
                            }
                        }
                    listOf(first.await(), second.await())
                }

            assertTrue(
                outcomes.any { it.isSuccess },
                "at least one of the two concurrent entries must succeed: $outcomes",
            )

            val survivingRecord =
                tx.transactional { repository.find(1, savedLocationId, vehicleId) }
            assertNotNull(survivingRecord, "the triple must exist after at least one successful write")
        }
    }

    @Test
    fun `find, recordEntryNotified, and markOutside round-trip correctly against a real database`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = ProximityNotificationRepositoryI()
            val (savedLocationId, vehicleId) =
                tx.transactional {
                    val company = CompanyRepositoryI().create(name = "FamilyFrost", inviteCode = "prox-roundtrip-code")
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

            val notifiedAt = Instant.now()
            tx.transactional { repository.recordEntryNotified(1, savedLocationId, vehicleId, notifiedAt) }
            val afterEntry = tx.transactional { repository.find(1, savedLocationId, vehicleId) }
            assertNotNull(afterEntry)
            assertTrue(afterEntry.currentlyInside)
            assertEquals(notifiedAt.epochSecond, afterEntry.lastNotifiedAt.epochSecond)

            tx.transactional { repository.markOutside(1, savedLocationId, vehicleId) }
            val afterExit = tx.transactional { repository.find(1, savedLocationId, vehicleId) }
            assertNotNull(afterExit)
            assertTrue(!afterExit.currentlyInside)
            assertEquals(
                notifiedAt.epochSecond,
                afterExit.lastNotifiedAt.epochSecond,
                "leaving must not touch lastNotifiedAt",
            )
        }
    }
}
