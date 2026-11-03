/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.core.utility.IdleSharedFlowSweeper
import com.mozgobolt.feature.vehicleTracking.domain.CellIndexer
import com.mozgobolt.feature.vehicleTracking.domain.model.CellId
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLiveEvent
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val TEST_AWAIT_TIMEOUT = 2.seconds

private suspend fun <T> Flow<T>.firstWithTimeout(): T = withTimeout(TEST_AWAIT_TIMEOUT) { first() }

// subscribeNear fans out through `merge`, which launches one child coroutine per matching cell —
// each needs a scheduler turn to attach before a publish can be seen. Repeating yield() a generous
// number of times is deterministic in practice; firstWithTimeout() protects against a hang.
private const val COLLECTOR_ATTACHMENT_YIELDS = 100

private suspend fun awaitAllCollectorsAttached() {
    repeat(COLLECTOR_ATTACHMENT_YIELDS) { yield() }
}

private fun VehicleLiveEvent.expectPosition(): VehicleLocationUpdate =
    when (this) {
        is VehicleLiveEvent.Position -> update
        is VehicleLiveEvent.Offline -> error("expected a Position event but got Offline(vehicleId=$vehicleId)")
    }

class InMemoryVehicleLocationHubTest {
    // Deterministic, geography-free stand-in: buckets whole-degree lat/lon into a cell, and
    // treats radiusKm as "how many whole-degree buckets to reach in each direction." Real
    // geospatial correctness (k-ring coverage, boundary handling) is H3CellIndexerTest's job —
    // this hub's own tests only need *some* partitioning scheme to exercise cell-scoped vs.
    // nearby subscription/snapshot behavior.
    private class FakeCellIndexer : CellIndexer {
        override fun cellFor(
            latitude: Double,
            longitude: Double,
        ): CellId = CellId("${latitude.toInt()}:${longitude.toInt()}")

        override fun cellsWithin(
            latitude: Double,
            longitude: Double,
            radiusKm: Double,
        ): Set<CellId> {
            val reach = radiusKm.toInt().coerceAtLeast(1)
            val baseLat = latitude.toInt()
            val baseLon = longitude.toInt()
            return buildSet {
                for (deltaLat in -reach..reach) {
                    for (deltaLon in -reach..reach) {
                        add(CellId("${baseLat + deltaLat}:${baseLon + deltaLon}"))
                    }
                }
            }
        }

        // Not exercised by this hub's own tests (see the class kdoc) — just needs to agree with
        // cellFor's own bucketing scheme so the interface contract holds.
        override fun centerOf(cellId: CellId): GeoPoint {
            val (lat, lon) = cellId.value.split(":").map { it.toDouble() }
            return GeoPoint(latitude = lat, longitude = lon)
        }
    }

    // Stateless/deterministic, so a fresh instance here always agrees with whichever
    // FakeCellIndexer a given test constructed its own hub with.
    private val cellIndexerForFixtures = FakeCellIndexer()

    private fun update(
        vehicleId: Int,
        latitude: Double,
        longitude: Double,
        assignmentId: Int? = null,
    ) = VehicleLocationUpdate(
        vehicleId = vehicleId,
        vendorUserId = vehicleId,
        latitude = latitude,
        longitude = longitude,
        recordedAt = Instant.now(),
        cellId = cellIndexerForFixtures.cellFor(latitude, longitude),
        assignmentId = assignmentId,
        sharesLocationWithCustomers = true,
    )

    @Test
    fun `subscribeNear receives an update published within the queried area`() =
        runBlocking {
            val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
            val received =
                async { hub.subscribeNear(latitude = 10.0, longitude = 10.0, radiusKm = 1.0).firstWithTimeout() }
            awaitAllCollectorsAttached()

            hub.publish(update(vehicleId = 1, latitude = 10.2, longitude = 10.2))

            assertEquals(1, received.await().expectPosition().vehicleId)
        }

    @Test
    fun `subscribeNear never receives an update published far outside the queried area`() =
        runBlocking {
            val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
            val nearby =
                async { hub.subscribeNear(latitude = 10.0, longitude = 10.0, radiusKm = 1.0).firstWithTimeout() }
            awaitAllCollectorsAttached()

            hub.publish(update(vehicleId = 99, latitude = 50.0, longitude = 50.0))
            hub.publish(update(vehicleId = 1, latitude = 10.0, longitude = 10.0))

            // If the far-away publish had leaked into this subscription, .first() would have
            // resolved to vehicle 99 instead — proving the partition actually isolates traffic.
            assertEquals(1, nearby.await().expectPosition().vehicleId)
        }

    @Test
    fun `subscribeNear with no cells covering the query completes immediately, empty`() =
        runBlocking {
            val hub =
                InMemoryVehicleLocationHub(
                    cellIndexer =
                        object : CellIndexer by FakeCellIndexer() {
                            override fun cellsWithin(
                                latitude: Double,
                                longitude: Double,
                                radiusKm: Double,
                            ) = emptySet<CellId>()
                        },
                )

            // emptyFlow() completes immediately without ever emitting — firstOrNull() returns
            // null for that; first() would throw NoSuchElementException instead of hanging. The
            // timeout here is a safety net against a future regression, not the thing under test.
            val result = withTimeout(TEST_AWAIT_TIMEOUT) { hub.subscribeNear(0.0, 0.0, 1.0).firstOrNull() }

            assertNull(result)
        }

    @Test
    fun `snapshotNear includes vehicles within the queried area and excludes those outside it`() {
        val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
        hub.publish(update(vehicleId = 1, latitude = 10.0, longitude = 10.0))
        hub.publish(update(vehicleId = 2, latitude = 50.0, longitude = 50.0))

        val snapshot = hub.snapshotNear(latitude = 10.0, longitude = 10.0, radiusKm = 1.0)

        assertEquals(listOf(1), snapshot.map { it.vehicleId })
    }

    @Test
    fun `an unconsented location remains private to movement validation`() {
        val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
        val privateUpdate =
            update(vehicleId = 1, latitude = 10.0, longitude = 10.0)
                .copy(sharesLocationWithCustomers = false)

        assertTrue(hub.publishIfCurrent(privateUpdate))

        assertTrue(hub.snapshotNear(10.0, 10.0, 1.0).isEmpty())
        assertEquals(privateUpdate, hub.lastKnownLocation(vehicleId = 1))
    }

    @Test
    fun `withdrawing location consent removes the public position and emits offline`() =
        runBlocking {
            val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
            val publicUpdate = update(vehicleId = 1, latitude = 10.0, longitude = 10.0, assignmentId = 10)
            assertTrue(hub.publishIfCurrent(publicUpdate))

            val offline =
                async { hub.subscribeNear(latitude = 10.0, longitude = 10.0, radiusKm = 1.0).firstWithTimeout() }
            awaitAllCollectorsAttached()
            val privateUpdate = publicUpdate.copy(sharesLocationWithCustomers = false)

            assertTrue(hub.publishIfCurrent(privateUpdate))

            assertEquals(VehicleLiveEvent.Offline(vehicleId = 1), offline.await())
            assertTrue(hub.snapshotNear(10.0, 10.0, 1.0).isEmpty())
            assertEquals(privateUpdate, hub.lastKnownLocation(1))
        }

    @Test
    fun `a legacy unassigned point cannot resurrect an ended assignment`() {
        val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
        val endedAssignment = update(vehicleId = 1, latitude = 10.0, longitude = 10.0, assignmentId = 10)
        assertTrue(hub.publishIfCurrent(endedAssignment))
        assertTrue(hub.markOffline(vehicleId = 1, assignmentId = 10))

        assertFalse(
            hub.publishIfCurrent(
                endedAssignment.copy(
                    assignmentId = null,
                    recordedAt = Instant.now().plusSeconds(1),
                ),
            ),
        )
        assertNull(hub.lastKnownLocation(1))
    }

    @Test
    fun `repeated offline signals for the ended assignment are idempotently accepted`() {
        val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
        assertTrue(hub.publishIfCurrent(update(vehicleId = 1, latitude = 10.0, longitude = 10.0, assignmentId = 10)))

        assertTrue(hub.markOffline(vehicleId = 1, assignmentId = 10))
        assertTrue(hub.markOffline(vehicleId = 1, assignmentId = 10))

        assertNull(hub.lastKnownLocation(1))
    }

    @Test
    fun `stale private location is removed without publishing an offline event`() =
        runBlocking {
            val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
            val privateUpdate =
                update(vehicleId = 1, latitude = 10.0, longitude = 10.0, assignmentId = 10)
                    .copy(sharesLocationWithCustomers = false)
            assertTrue(hub.publishIfCurrent(privateUpdate))
            val nextPosition =
                async { hub.subscribeNear(latitude = 10.0, longitude = 10.0, radiusKm = 1.0).firstWithTimeout() }
            awaitAllCollectorsAttached()

            hub.sweepStaleLocations(System.currentTimeMillis() + 6 * 60 * 1000)
            hub.publish(update(vehicleId = 2, latitude = 10.0, longitude = 10.0))

            assertEquals(2, nextPosition.await().expectPosition().vehicleId)
            assertNull(hub.lastKnownLocation(1))
        }

    @Test
    fun `a live location expires and notifies nearby subscribers after five minutes`() =
        runBlocking {
            val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
            val update = update(vehicleId = 1, latitude = 10.0, longitude = 10.0)
            hub.publish(update)
            val offline =
                async { hub.subscribeNear(latitude = 10.0, longitude = 10.0, radiusKm = 1.0).firstWithTimeout() }
            awaitAllCollectorsAttached()

            hub.sweepStaleLocations(System.currentTimeMillis() + 6 * 60 * 1000)

            assertEquals(VehicleLiveEvent.Offline(vehicleId = 1), offline.await())
            assertTrue(hub.snapshotNear(10.0, 10.0, 1.0).isEmpty())
            assertNull(hub.lastKnownLocation(1))
        }

    @Test
    fun `a later publish for the same vehicle replaces its nearby snapshot position`() {
        val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
        hub.publish(update(vehicleId = 1, latitude = 10.0, longitude = 10.0))
        hub.publish(update(vehicleId = 1, latitude = 10.1, longitude = 10.1))

        val snapshot = hub.snapshotNear(10.0, 10.0, 1.0)
        assertEquals(1, snapshot.size)
        assertEquals(10.1, snapshot.single().latitude)
    }

    @Test
    fun `publishing with no subscriber anywhere near is a no-op, not an error`() {
        val hub = InMemoryVehicleLocationHub(FakeCellIndexer())

        hub.publish(update(vehicleId = 1, latitude = 10.0, longitude = 10.0))
        // No assertion beyond "this doesn't throw" — nobody has subscribed to this cell yet, so
        // there's no flow to emit into and nothing to observe.

        assertTrue(hub.snapshotNear(10.0, 10.0, 1.0).isNotEmpty())
    }

    @Test
    fun `lastKnownLocation is null for a vehicle that has never published`() {
        val hub = InMemoryVehicleLocationHub(FakeCellIndexer())

        assertNull(hub.lastKnownLocation(vehicleId = 999))
    }

    @Test
    fun `lastKnownLocation reflects the most recent publish for that vehicle only`() {
        val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
        hub.publish(update(vehicleId = 1, latitude = 10.0, longitude = 10.0))
        hub.publish(update(vehicleId = 2, latitude = 20.0, longitude = 20.0))
        hub.publish(update(vehicleId = 1, latitude = 10.5, longitude = 10.5))

        assertEquals(10.5, hub.lastKnownLocation(vehicleId = 1)?.latitude)
        assertEquals(20.0, hub.lastKnownLocation(vehicleId = 2)?.latitude)
    }

    @Test
    fun `markOffline removes the vehicle from nearby snapshots`() {
        val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
        hub.publish(update(vehicleId = 1, latitude = 10.0, longitude = 10.0))
        hub.publish(update(vehicleId = 2, latitude = 20.0, longitude = 20.0))

        hub.markOffline(vehicleId = 1)

        assertEquals(listOf(2), hub.snapshotNear(20.0, 20.0, 1.0).map { it.vehicleId })
        assertTrue(hub.snapshotNear(10.0, 10.0, 1.0).isEmpty())
        assertNull(hub.lastKnownLocation(vehicleId = 1))
    }

    @Test
    fun `late points and offline signals from an ended assignment cannot affect a newer shift`() {
        val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
        val oldAssignment = update(vehicleId = 1, latitude = 10.0, longitude = 10.0, assignmentId = 10)
        val newAssignment = update(vehicleId = 1, latitude = 20.0, longitude = 20.0, assignmentId = 11)

        assertTrue(hub.publishIfCurrent(oldAssignment))
        assertTrue(hub.markOffline(vehicleId = 1, assignmentId = 10))
        assertNull(hub.lastKnownLocation(vehicleId = 1))

        assertTrue(hub.publishIfCurrent(newAssignment))
        assertFalse(hub.publishIfCurrent(oldAssignment.copy(recordedAt = Instant.now().plusSeconds(1))))
        assertFalse(hub.markOffline(vehicleId = 1, assignmentId = 10))

        assertEquals(newAssignment, hub.lastKnownLocation(vehicleId = 1))
    }

    @Test
    fun `expired ended-assignment fences can be reclaimed`() {
        val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
        val oldAssignment = update(vehicleId = 1, latitude = 10.0, longitude = 10.0, assignmentId = 10)

        assertTrue(hub.publishIfCurrent(oldAssignment))
        assertTrue(hub.markOffline(vehicleId = 1, assignmentId = 10))
        assertFalse(hub.publishIfCurrent(oldAssignment))

        hub.pruneExpiredAssignmentFences(
            Instant.now().plusSeconds(ASSIGNMENT_FENCE_RETENTION_SECONDS + 1),
        )

        assertTrue(hub.publishIfCurrent(oldAssignment.copy(recordedAt = Instant.now().plusSeconds(1))))
    }

    @Test
    fun `markOffline for a vehicle that never published is a safe no-op`() {
        val hub = InMemoryVehicleLocationHub(FakeCellIndexer())

        hub.markOffline(vehicleId = 999)

        assertTrue(hub.snapshotNear(0.0, 0.0, 1.0).isEmpty())
    }

    @Test
    fun `markOffline emits an Offline event to subscribers near the vehicle's last-known cell`() =
        runBlocking {
            val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
            hub.publish(update(vehicleId = 1, latitude = 10.0, longitude = 10.0))

            val nearReceived =
                async { hub.subscribeNear(latitude = 10.0, longitude = 10.0, radiusKm = 1.0).firstWithTimeout() }
            awaitAllCollectorsAttached()

            hub.markOffline(vehicleId = 1)

            assertEquals(VehicleLiveEvent.Offline(vehicleId = 1), nearReceived.await())
        }

    @Test
    fun `markOffline never reaches a subscriber near an unrelated cell`() =
        runBlocking {
            val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
            hub.publish(update(vehicleId = 1, latitude = 10.0, longitude = 10.0))

            val farAway =
                async { hub.subscribeNear(latitude = 80.0, longitude = 80.0, radiusKm = 1.0).firstWithTimeout() }
            awaitAllCollectorsAttached()

            hub.markOffline(vehicleId = 1)
            // Prove absence, not just "didn't crash": publish something the far-away subscriber
            // DOES expect, and confirm that's what actually arrives — if the offline event had
            // leaked into this subscription instead, .first() would already have resolved to it.
            hub.publish(update(vehicleId = 2, latitude = 80.0, longitude = 80.0))

            assertEquals(2, farAway.await().expectPosition().vehicleId)
        }

    @Test
    fun `a cell with an active subscriber is untouched by a sweep, and keeps delivering`() =
        runBlocking {
            val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
            val received =
                async { hub.subscribeNear(latitude = 10.0, longitude = 10.0, radiusKm = 1.0).firstWithTimeout() }
            awaitAllCollectorsAttached()

            IdleSharedFlowSweeper(hub.flowsByCell, ApplicationBackgroundScope()).sweepOnce()
            hub.publish(update(vehicleId = 1, latitude = 10.0, longitude = 10.0))

            assertEquals(1, received.await().expectPosition().vehicleId)
        }

    @Test
    fun `after a sweep evicts an idle cell, subscribing near that same cell again works normally`() =
        runBlocking {
            val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
            val firstReceived =
                async { hub.subscribeNear(latitude = 10.0, longitude = 10.0, radiusKm = 1.0).firstWithTimeout() }
            awaitAllCollectorsAttached()
            hub.publish(update(vehicleId = 1, latitude = 10.0, longitude = 10.0))
            assertEquals(1, firstReceived.await().expectPosition().vehicleId)

            // Nobody is subscribed right now (the collector above already completed on its
            // first element) — the cell's flow is genuinely idle and should be evicted.
            IdleSharedFlowSweeper(hub.flowsByCell, ApplicationBackgroundScope()).sweepOnce()

            val secondReceived =
                async { hub.subscribeNear(latitude = 10.0, longitude = 10.0, radiusKm = 1.0).firstWithTimeout() }
            awaitAllCollectorsAttached()
            hub.publish(update(vehicleId = 2, latitude = 10.0, longitude = 10.0))

            assertEquals(2, secondReceived.await().expectPosition().vehicleId)
        }
}
