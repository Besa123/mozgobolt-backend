package com.mozgobolt.core.data.messaging

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class NoOpMessageRelayTest {
    @Test
    fun `start and stop are safe to call, in any order, any number of times`() {
        val relay = NoOpMessageRelay()

        relay.start()
        relay.stop()
        relay.stop()
        relay.start()
        // No assertion beyond "none of this throws" — there is no real connection underneath to
        // get into a bad state.
    }

    @Test
    fun `publish never throws and has no observable effect`() =
        runBlocking {
            val relay = NoOpMessageRelay()

            relay.publish("any-channel", "any-message")
        }

    @Test
    fun `subscribe yields nothing and completes immediately`() =
        runBlocking {
            val relay = NoOpMessageRelay()

            val received = relay.subscribe("any-channel").toList()

            assertTrue(received.isEmpty())
        }
}
