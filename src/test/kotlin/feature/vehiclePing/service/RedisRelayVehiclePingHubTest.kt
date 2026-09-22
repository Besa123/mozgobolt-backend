package com.mozgobolt.feature.vehiclePing.service

import com.mozgobolt.core.data.messaging.FakeMessageRelay
import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePingNotification
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val TEST_AWAIT_TIMEOUT = 2.seconds

// Only bounds a negative assertion ("this never arrives") — see NEGATIVE_CHECK_BUDGET's twin in
// RedisRelayVehicleLocationHubTest for why this stays short rather than reusing TEST_AWAIT_TIMEOUT.
private val NEGATIVE_CHECK_BUDGET = 200.milliseconds

private fun notification(vehicleId: Int) =
    VehiclePingNotification(vehicleId = vehicleId, sentAt = Instant.now(), latitude = 47.4979, longitude = 19.0402)

class RedisRelayVehiclePingHubTest {
    @Test
    fun `publish updates this instance's own local hub immediately`() =
        runBlocking {
            val hub = RedisRelayVehiclePingHub(InMemoryVehiclePingHub(), FakeMessageRelay())
            val received = async { withTimeout(TEST_AWAIT_TIMEOUT) { hub.subscribe(vendorUserId = 1).first() } }
            yield() // one collector, one flow — a single scheduler turn is enough to attach.

            hub.publish(vendorUserId = 1, notification = notification(vehicleId = 10))

            assertEquals(10, received.await().vehicleId)
        }

    @Test
    fun `publish relays to the shared channel`() =
        runBlocking {
            val relay = FakeMessageRelay()
            val hub = RedisRelayVehiclePingHub(InMemoryVehiclePingHub(), relay)

            hub.publish(vendorUserId = 1, notification = notification(vehicleId = 7))

            withTimeout(TEST_AWAIT_TIMEOUT) {
                while (relay.published.isEmpty()) delay(10)
            }
            assertEquals(VEHICLE_PING_CHANNEL, relay.published.single().channel)
        }

    @Test
    fun `a second instance sharing the same relay receives a ping this instance published`() =
        runBlocking {
            val relay = FakeMessageRelay()
            val hubA = RedisRelayVehiclePingHub(InMemoryVehiclePingHub(), relay)
            val hubB = RedisRelayVehiclePingHub(InMemoryVehiclePingHub(), relay)

            hubB.start()
            withTimeout(TEST_AWAIT_TIMEOUT) { relay.awaitSubscriber(VEHICLE_PING_CHANNEL) }

            val onB = async { withTimeout(TEST_AWAIT_TIMEOUT) { hubB.subscribe(vendorUserId = 5).first() } }
            yield() // this subscription runs on the test's own dispatcher — one turn attaches it.

            hubA.publish(vendorUserId = 5, notification = notification(vehicleId = 99))

            assertEquals(99, onB.await().vehicleId)
            hubB.stop()
        }

    @Test
    fun `a malformed message on the relay is skipped, not fatal to the collector loop`() =
        runBlocking {
            val relay = FakeMessageRelay()
            val hubB = RedisRelayVehiclePingHub(InMemoryVehiclePingHub(), relay)
            hubB.start()
            withTimeout(TEST_AWAIT_TIMEOUT) { relay.awaitSubscriber(VEHICLE_PING_CHANNEL) }

            relay.publish(VEHICLE_PING_CHANNEL, "not valid json")

            val onB = async { withTimeout(TEST_AWAIT_TIMEOUT) { hubB.subscribe(vendorUserId = 5).first() } }
            yield()

            val hubA = RedisRelayVehiclePingHub(InMemoryVehiclePingHub(), relay)
            hubA.publish(vendorUserId = 5, notification = notification(vehicleId = 3))

            assertEquals(3, onB.await().vehicleId)
            hubB.stop()
        }

    @Test
    fun `stop cancels the relay-consuming loop, so a later relayed ping never arrives`() =
        runBlocking {
            val relay = FakeMessageRelay()
            val hubB = RedisRelayVehiclePingHub(InMemoryVehiclePingHub(), relay)
            hubB.start()
            withTimeout(TEST_AWAIT_TIMEOUT) { relay.awaitSubscriber(VEHICLE_PING_CHANNEL) }
            hubB.stop()

            val hubA = RedisRelayVehiclePingHub(InMemoryVehiclePingHub(), relay)
            hubA.publish(vendorUserId = 5, notification = notification(vehicleId = 1))

            val arrivedAnyway =
                withTimeoutOrNull(NEGATIVE_CHECK_BUDGET) { hubB.subscribe(vendorUserId = 5).first() }
            assertNull(arrivedAnyway)
        }
}
