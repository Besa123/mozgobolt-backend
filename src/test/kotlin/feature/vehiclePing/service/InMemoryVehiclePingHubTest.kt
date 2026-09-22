package com.mozgobolt.feature.vehiclePing.service

import com.mozgobolt.core.utility.IdleSharedFlowSweeper
import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePingNotification
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** Same safety-net reasoning as `InMemoryVehicleLocationHubTest`'s identical constant: a bug that
 * broke delivery should fail this test in 2 seconds with a clear message, not hang the suite. */
private val TEST_AWAIT_TIMEOUT = 2.seconds

class InMemoryVehiclePingHubTest {
    private fun notification(vehicleId: Int) =
        VehiclePingNotification(vehicleId = vehicleId, sentAt = Instant.now(), latitude = 47.4979, longitude = 19.0402)

    @Test
    fun `a subscribed vendor receives a notification published to their own user id`() =
        runBlocking {
            val hub = InMemoryVehiclePingHub()
            val received = async { withTimeout(TEST_AWAIT_TIMEOUT) { hub.subscribe(vendorUserId = 500).first() } }
            yield() // one collector, one flow — a single scheduler turn is enough to attach.

            hub.publish(vendorUserId = 500, notification = notification(vehicleId = 10))

            assertEquals(10, received.await().vehicleId)
        }

    @Test
    fun `a notification published to a different vendor never reaches this vendor's subscription`() =
        runBlocking {
            val hub = InMemoryVehiclePingHub()
            val forVendorA = async { withTimeout(TEST_AWAIT_TIMEOUT) { hub.subscribe(vendorUserId = 500).first() } }
            yield()

            hub.publish(vendorUserId = 600, notification = notification(vehicleId = 20))
            hub.publish(vendorUserId = 500, notification = notification(vehicleId = 10))

            // If vendor 600's publish had leaked into vendor 500's subscription, .first() would
            // have resolved to vehicle 20 instead — proving the per-vendor partition isolates it.
            assertEquals(10, forVendorA.await().vehicleId)
        }

    @Test
    fun `publishing to a vendor who has never subscribed is a no-op, not an error`() {
        val hub = InMemoryVehiclePingHub()

        hub.publish(vendorUserId = 999, notification = notification(vehicleId = 1))
        // No assertion beyond "this doesn't throw" — nobody has ever subscribed as vendor 999, so
        // there is no flow to emit into.
    }

    @Test
    fun `two independent subscribers for the same vendor both receive the same notification`() =
        runBlocking {
            val hub = InMemoryVehiclePingHub()
            val first = async { withTimeout(TEST_AWAIT_TIMEOUT) { hub.subscribe(vendorUserId = 500).first() } }
            val second = async { withTimeout(TEST_AWAIT_TIMEOUT) { hub.subscribe(vendorUserId = 500).first() } }
            yield()
            yield()

            hub.publish(vendorUserId = 500, notification = notification(vehicleId = 42))

            assertEquals(42, first.await().vehicleId)
            assertEquals(42, second.await().vehicleId)
        }

    @Test
    fun `a vendor's flow with an active subscriber is untouched by a sweep, and keeps delivering`() =
        runBlocking {
            val hub = InMemoryVehiclePingHub()
            val received = async { withTimeout(TEST_AWAIT_TIMEOUT) { hub.subscribe(vendorUserId = 500).first() } }
            yield()

            IdleSharedFlowSweeper(hub.flowsByVendorUserId).sweepOnce()
            hub.publish(vendorUserId = 500, notification = notification(vehicleId = 10))

            assertEquals(10, received.await().vehicleId)
        }

    @Test
    fun `after a sweep evicts an idle vendor's flow, subscribing as that same vendor again works normally`() =
        runBlocking {
            val hub = InMemoryVehiclePingHub()
            val firstReceived =
                async { withTimeout(TEST_AWAIT_TIMEOUT) { hub.subscribe(vendorUserId = 500).first() } }
            yield()
            hub.publish(vendorUserId = 500, notification = notification(vehicleId = 10))
            assertEquals(10, firstReceived.await().vehicleId)

            // The collector above already completed on its first element — nobody is subscribed
            // as vendor 500 right now, so that flow is genuinely idle and should be evicted.
            IdleSharedFlowSweeper(hub.flowsByVendorUserId).sweepOnce()

            val secondReceived =
                async { withTimeout(TEST_AWAIT_TIMEOUT) { hub.subscribe(vendorUserId = 500).first() } }
            yield()
            hub.publish(vendorUserId = 500, notification = notification(vehicleId = 20))

            assertEquals(20, secondReceived.await().vehicleId)
        }
}
