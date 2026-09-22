package com.mozgobolt.feature.sync.service

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InMemorySyncEventHubTest {
    @Test
    fun `a subscriber receives a published event for its own user id`() =
        runBlocking {
            val hub = InMemorySyncEventHub()
            val received = async { hub.subscribe(1).first() }
            yield()

            hub.publish(1, 42L)

            assertEquals(42L, received.await().eventId)
        }

    @Test
    fun `publishing for one user never reaches another user's subscriber`() =
        runBlocking {
            val hub = InMemorySyncEventHub()
            val user1Received = async { hub.subscribe(1).first() }
            val user2Received = async { hub.subscribe(2).first() }
            yield()

            hub.publish(1, 42L)
            hub.publish(2, 99L)

            assertEquals(42L, user1Received.await().eventId)
            assertEquals(99L, user2Received.await().eventId)
        }

    @Test
    fun `two devices logged into the same user both receive the same published hint`() =
        runBlocking {
            val hub = InMemorySyncEventHub()
            // Same user id subscribed twice, standing in for two devices (e.g. phone + tablet)
            // signed into one account — the hub has no concept of "device," only "user."
            val device1Received = async { hub.subscribe(1).first() }
            val device2Received = async { hub.subscribe(1).first() }
            yield()

            hub.publish(1, 42L)

            assertEquals(42L, device1Received.await().eventId)
            assertEquals(42L, device2Received.await().eventId)
        }

    @Test
    fun `publishing with an origin device id is carried through to the subscriber`() =
        runBlocking {
            val hub = InMemorySyncEventHub()
            val received = async { hub.subscribe(1).first() }
            yield()

            hub.publish(1, 42L, originDeviceId = "device-A")

            val hint = received.await()
            assertEquals(42L, hint.eventId)
            assertEquals("device-A", hint.originDeviceId)
        }

    @Test
    fun `publishing with no origin device id leaves it null`() =
        runBlocking {
            val hub = InMemorySyncEventHub()
            val received = async { hub.subscribe(1).first() }
            yield()

            hub.publish(1, 42L)

            assertNull(received.await().originDeviceId)
        }

    @Test
    fun `publishing with no subscriber is a no-op, not an error`() {
        val hub = InMemorySyncEventHub()

        hub.publish(1, 42L)
        // No assertion needed beyond "this doesn't throw" — there's no subscriber to observe it,
        // and that's the whole point: a hint that's dropped is never a correctness problem.
    }
}
