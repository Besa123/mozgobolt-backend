package com.mozgobolt.feature.vehicleTracking.service

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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A test-suite safety net, not a production timing guarantee: every test below that waits on a
 * Flow wraps the wait in this. `subscribeNear` fans out into one collector coroutine per matching
 * cell (via `merge`), so — unlike subscribing to a single flow directly — a bug that breaks that
 * fan-out could leave `.first()` waiting forever with nothing to time it out. This turns "the
 * whole suite hangs" into "this one test fails in 2 seconds with a clear message."
 */
private val TEST_AWAIT_TIMEOUT = 2.seconds

private suspend fun <T> Flow<T>.firstWithTimeout(): T = withTimeout(TEST_AWAIT_TIMEOUT) { first() }

// A single yield() is enough when a test subscribes to one flow directly (subscribeAll), because
// there's exactly one coroutine that needs to reach its collect point. subscribeNear fans out
// through `merge`, which launches one additional child coroutine per matching cell — each of
// those needs its own scheduler turn to actually attach before a publish can be seen. Repeating
// yield() a generous number of times is the standard, deterministic-in-practice fix for this
// (this is exactly how kotlinx.coroutines' own test suite waits out this class of fan-out); the
// firstWithTimeout() safety net above is what actually protects against this silently regressing
// into a hang again rather than a fast, clear test failure.
private const val COLLECTOR_ATTACHMENT_YIELDS = 100

private suspend fun awaitAllCollectorsAttached() {
    repeat(COLLECTOR_ATTACHMENT_YIELDS) { yield() }
}

/** Every existing test in this file only ever expects a position, never an offline event — this
 * unwraps that expectation instead of every test doing an unchecked cast itself. */
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
    // unfiltered subscription/snapshot behavior.
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
    ) = VehicleLocationUpdate(
        vehicleId = vehicleId,
        vendorUserId = vehicleId,
        latitude = latitude,
        longitude = longitude,
        recordedAt = Instant.now(),
        cellId = cellIndexerForFixtures.cellFor(latitude, longitude),
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
    fun `subscribeAll receives an update regardless of where it was published`() =
        runBlocking {
            val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
            val received = async { hub.subscribeAll().firstWithTimeout() }
            awaitAllCollectorsAttached()

            hub.publish(update(vehicleId = 42, latitude = 50.0, longitude = 50.0))

            assertEquals(42, received.await().expectPosition().vehicleId)
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
    fun `snapshotAll includes every published vehicle regardless of location`() {
        val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
        hub.publish(update(vehicleId = 1, latitude = 10.0, longitude = 10.0))
        hub.publish(update(vehicleId = 2, latitude = 50.0, longitude = 50.0))

        val snapshot = hub.snapshotAll()

        assertEquals(setOf(1, 2), snapshot.map { it.vehicleId }.toSet())
    }

    @Test
    fun `a later publish for the same vehicle replaces the earlier one in both snapshots`() {
        val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
        hub.publish(update(vehicleId = 1, latitude = 10.0, longitude = 10.0))
        hub.publish(update(vehicleId = 1, latitude = 10.1, longitude = 10.1))

        assertEquals(1, hub.snapshotAll().size)
        assertEquals(10.1, hub.snapshotAll().single().latitude)
        assertEquals(10.1, hub.snapshotNear(10.0, 10.0, 1.0).single().latitude)
    }

    @Test
    fun `publishing with no subscriber anywhere near is a no-op, not an error`() {
        val hub = InMemoryVehicleLocationHub(FakeCellIndexer())

        hub.publish(update(vehicleId = 1, latitude = 10.0, longitude = 10.0))
        // No assertion beyond "this doesn't throw" — nobody has subscribed to this cell yet, so
        // there's no flow to emit into and nothing to observe.

        assertTrue(hub.snapshotAll().isNotEmpty())
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
    fun `markOffline removes the vehicle from both snapshots`() {
        val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
        hub.publish(update(vehicleId = 1, latitude = 10.0, longitude = 10.0))
        hub.publish(update(vehicleId = 2, latitude = 20.0, longitude = 20.0))

        hub.markOffline(vehicleId = 1)

        assertEquals(setOf(2), hub.snapshotAll().map { it.vehicleId }.toSet())
        assertTrue(hub.snapshotNear(10.0, 10.0, 1.0).isEmpty())
        assertNull(hub.lastKnownLocation(vehicleId = 1))
    }

    @Test
    fun `markOffline for a vehicle that never published is a safe no-op`() {
        val hub = InMemoryVehicleLocationHub(FakeCellIndexer())

        hub.markOffline(vehicleId = 999)

        assertTrue(hub.snapshotAll().isEmpty())
    }

    @Test
    fun `markOffline emits an Offline event to subscribers of the vehicle's last-known cell and to subscribeAll`() =
        runBlocking {
            val hub = InMemoryVehicleLocationHub(FakeCellIndexer())
            hub.publish(update(vehicleId = 1, latitude = 10.0, longitude = 10.0))

            val nearReceived =
                async { hub.subscribeNear(latitude = 10.0, longitude = 10.0, radiusKm = 1.0).firstWithTimeout() }
            val allReceived = async { hub.subscribeAll().firstWithTimeout() }
            awaitAllCollectorsAttached()

            hub.markOffline(vehicleId = 1)

            assertEquals(VehicleLiveEvent.Offline(vehicleId = 1), nearReceived.await())
            assertEquals(VehicleLiveEvent.Offline(vehicleId = 1), allReceived.await())
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

            IdleSharedFlowSweeper(hub.flowsByCell).sweepOnce()
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
            IdleSharedFlowSweeper(hub.flowsByCell).sweepOnce()

            val secondReceived =
                async { hub.subscribeNear(latitude = 10.0, longitude = 10.0, radiusKm = 1.0).firstWithTimeout() }
            awaitAllCollectorsAttached()
            hub.publish(update(vehicleId = 2, latitude = 10.0, longitude = 10.0))

            assertEquals(2, secondReceived.await().expectPosition().vehicleId)
        }
}
