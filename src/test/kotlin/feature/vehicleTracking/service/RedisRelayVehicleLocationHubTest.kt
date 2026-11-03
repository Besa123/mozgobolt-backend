/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.core.data.messaging.FakeMessageRelay
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val TEST_AWAIT_TIMEOUT = 2.seconds

// Deliberately short: this only bounds how long a negative assertion ("this never arrives") waits
// before concluding absence — unlike TEST_AWAIT_TIMEOUT, which bounds a positive assertion that
// would otherwise hang forever on a real regression. A short negative-check budget just keeps that
// one test from needlessly taking multiple seconds every run.
private val NEGATIVE_CHECK_BUDGET = 200.milliseconds
private const val TEST_LATITUDE = 47.4979
private const val TEST_LONGITUDE = 19.0402

class RedisRelayVehicleLocationHubTest {
    private val cellIndexer = H3CellIndexer()

    private fun relayHub(relay: FakeMessageRelay) =
        RedisRelayVehicleLocationHub(
            delegate = InMemoryVehicleLocationHub(cellIndexer),
            relay = relay,
            backgroundScope = ApplicationBackgroundScope(),
        )

    private fun update(vehicleId: Int) =
        VehicleLocationUpdate(
            vehicleId = vehicleId,
            vendorUserId = vehicleId,
            latitude = TEST_LATITUDE,
            longitude = TEST_LONGITUDE,
            recordedAt = Instant.now(),
            cellId = cellIndexer.cellFor(TEST_LATITUDE, TEST_LONGITUDE),
        )

    @Test
    fun `publish updates this instance's own local hub immediately`() {
        val hub = relayHub(FakeMessageRelay())

        hub.publish(update(vehicleId = 1))

        assertEquals(1, hub.lastKnownLocation(vehicleId = 1)?.vehicleId)
    }

    @Test
    fun `publish relays the update to the shared channel`() =
        runBlocking {
            val relay = FakeMessageRelay()
            val hub = relayHub(relay)

            hub.publish(update(vehicleId = 7))

            // publish() relays on a background coroutine launched from hub's own scope — poll
            // briefly rather than assuming it's already landed by the time publish() returns.
            withTimeout(TEST_AWAIT_TIMEOUT) {
                while (relay.published.isEmpty()) delay(10)
            }
            assertEquals(VEHICLE_LOCATION_CHANNEL, relay.published.single().channel)
        }

    // hubA's publish() relays to a real background dispatcher (Dispatchers.IO), and hubB's own
    // relay-consuming loop runs on another — a cross-thread hop, unlike this test suite's other
    // single-dispatcher hubs where a yield() is enough. lastKnownLocation() is a plain
    // ConcurrentHashMap read, safe to poll from the test's own coroutine while that hop completes.
    //
    // A plain poll loop bounded by a deadline, not a nested withTimeout: withTimeoutOrNull only
    // swallows a TimeoutCancellationException tied to its *own* job, so wrapping a second,
    // independently-timing-out withTimeout inside it doesn't work — that inner exception just
    // propagates straight through as an uncaught failure instead of yielding null.
    private suspend fun pollLastKnownLocation(
        hub: VehicleLocationHub,
        vehicleId: Int,
        budget: Duration = TEST_AWAIT_TIMEOUT,
    ): VehicleLocationUpdate? {
        val deadline = TimeSource.Monotonic.markNow() + budget
        while (true) {
            hub.lastKnownLocation(vehicleId)?.let { return it }
            if (deadline.hasPassedNow()) return null
            delay(10)
        }
    }

    @Test
    fun `a second instance sharing the same relay receives an update this instance published`() =
        runBlocking {
            val relay = FakeMessageRelay()
            val hubA = relayHub(relay)
            val hubB = relayHub(relay)

            hubB.start()
            withTimeout(TEST_AWAIT_TIMEOUT) { relay.awaitSubscriber(VEHICLE_LOCATION_CHANNEL) }

            hubA.publish(update(vehicleId = 42))

            assertEquals(42, pollLastKnownLocation(hubB, vehicleId = 42)?.vehicleId)
            hubB.stop()
        }

    @Test
    fun `assignment-scoped offline relay rejects late old-session points on other instances`() =
        runBlocking {
            val relay = FakeMessageRelay()
            val hubA = relayHub(relay)
            val hubB = relayHub(relay)
            val oldAssignment = update(vehicleId = 42).copy(assignmentId = 10)
            val newAssignment = update(vehicleId = 42).copy(assignmentId = 11)

            hubB.start()
            try {
                withTimeout(TEST_AWAIT_TIMEOUT) { relay.awaitSubscriber(VEHICLE_LOCATION_CHANNEL) }

                assertTrue(hubA.publishIfCurrent(oldAssignment))
                awaitRelayCount(relay, 1)
                awaitAssignment(hubB, vehicleId = 42, assignmentId = 10)

                assertTrue(hubA.markOffline(vehicleId = 42, assignmentId = 10))
                awaitRelayCount(relay, 2)
                awaitOffline(hubB, vehicleId = 42)
                assertNull(hubB.lastKnownLocation(vehicleId = 42))

                assertFalse(hubA.publishIfCurrent(oldAssignment.copy(recordedAt = Instant.now().plusSeconds(1))))
                assertEquals(2, relay.published.size, "an ended assignment must not relay another location")

                assertTrue(hubA.publishIfCurrent(newAssignment))
                awaitRelayCount(relay, 3)
                awaitAssignment(hubB, vehicleId = 42, assignmentId = 11)

                assertFalse(hubA.markOffline(vehicleId = 42, assignmentId = 10))
                assertEquals(3, relay.published.size, "an old offline signal must not be relayed")
                assertEquals(11, hubB.lastKnownLocation(vehicleId = 42)?.assignmentId)
            } finally {
                hubB.stop()
            }
        }

    private suspend fun awaitRelayCount(
        relay: FakeMessageRelay,
        expectedCount: Int,
    ) {
        withTimeout(TEST_AWAIT_TIMEOUT) {
            while (relay.published.size < expectedCount) delay(10)
        }
    }

    private suspend fun awaitAssignment(
        hub: VehicleLocationHub,
        vehicleId: Int,
        assignmentId: Int,
    ) {
        withTimeout(TEST_AWAIT_TIMEOUT) {
            while (hub.lastKnownLocation(vehicleId)?.assignmentId != assignmentId) delay(10)
        }
    }

    private suspend fun awaitOffline(
        hub: VehicleLocationHub,
        vehicleId: Int,
    ) {
        withTimeout(TEST_AWAIT_TIMEOUT) {
            while (hub.lastKnownLocation(vehicleId) != null) delay(10)
        }
    }

    @Test
    fun `a malformed message on the relay is skipped, not fatal to the collector loop`() =
        runBlocking {
            val relay = FakeMessageRelay()
            val hubB = relayHub(relay)
            hubB.start()
            withTimeout(TEST_AWAIT_TIMEOUT) { relay.awaitSubscriber(VEHICLE_LOCATION_CHANNEL) }

            relay.publish(VEHICLE_LOCATION_CHANNEL, "not valid json")

            val hubA = relayHub(relay)
            hubA.publish(update(vehicleId = 5))

            assertEquals(5, pollLastKnownLocation(hubB, vehicleId = 5)?.vehicleId)
            hubB.stop()
        }

    @Test
    fun `stop cancels the relay-consuming loop, so a later relayed update never arrives`() =
        runBlocking {
            val relay = FakeMessageRelay()
            val hubB = relayHub(relay)
            hubB.start()
            withTimeout(TEST_AWAIT_TIMEOUT) { relay.awaitSubscriber(VEHICLE_LOCATION_CHANNEL) }
            hubB.stop()

            val hubA = relayHub(relay)
            hubA.publish(update(vehicleId = 9))

            val arrivedAnyway = pollLastKnownLocation(hubB, vehicleId = 9, budget = NEGATIVE_CHECK_BUDGET)
            assertNull(arrivedAnyway)
        }
}
