package com.mozgobolt.core.utility

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Same safety-net reasoning as the vehicle-tracking/ping hub tests' identical constant: a bug
 * here should fail this test in 2 seconds with a clear message, not hang the suite. */
private val TEST_AWAIT_TIMEOUT = 2.seconds

private fun newFlow() =
    MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

class IdleSharedFlowSweeperTest {
    @Test
    fun `a flow with an active subscriber survives a sweep`() =
        runBlocking {
            val flows = ConcurrentHashMap<String, MutableSharedFlow<String>>()
            val flow = newFlow()
            flows["key"] = flow
            val sweeper = IdleSharedFlowSweeper(flows)

            val collected = async { withTimeout(TEST_AWAIT_TIMEOUT) { flow.first() } }
            yield() // one collector, one flow — a single scheduler turn is enough to attach.

            sweeper.sweepOnce()
            assertTrue(flows.containsKey("key"))

            // Prove the surviving entry is still the live thing, not just present-but-dead:
            // a fresh emission must still reach the collector that attached before the sweep.
            flow.tryEmit("still here")
            assertEquals("still here", collected.await())
        }

    @Test
    fun `a flow with zero subscribers is evicted by a sweep`() {
        val flows = ConcurrentHashMap<String, MutableSharedFlow<String>>()
        flows["key"] = newFlow()
        val sweeper = IdleSharedFlowSweeper(flows)

        sweeper.sweepOnce()

        assertFalse(flows.containsKey("key"))
    }

    @Test
    fun `sweeping an empty map is a safe no-op`() {
        val flows = ConcurrentHashMap<String, MutableSharedFlow<String>>()
        val sweeper = IdleSharedFlowSweeper(flows)

        sweeper.sweepOnce()

        assertTrue(flows.isEmpty())
    }

    @Test
    fun `repeated sweeps never evict a flow that keeps a subscriber attached the whole time`() =
        runBlocking {
            val flows = ConcurrentHashMap<String, MutableSharedFlow<String>>()
            val flow = newFlow()
            flows["key"] = flow
            val sweeper = IdleSharedFlowSweeper(flows)

            val collected = async { withTimeout(TEST_AWAIT_TIMEOUT) { flow.first() } }
            yield()

            repeat(3) { sweeper.sweepOnce() }
            assertTrue(flows.containsKey("key"))

            flow.tryEmit("value")
            assertEquals("value", collected.await())
        }

    @Test
    fun `an idle flow is evicted while an unrelated key's idle flow is independently evicted too`() {
        val flows = ConcurrentHashMap<String, MutableSharedFlow<String>>()
        flows["a"] = newFlow()
        flows["b"] = newFlow()
        val sweeper = IdleSharedFlowSweeper(flows)

        sweeper.sweepOnce()

        assertTrue(flows.isEmpty())
    }
}
