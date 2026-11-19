/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.messaging

import com.mozgobolt.core.data.redis.REDIS_COMPATIBLE_SERVERS
import com.mozgobolt.core.data.redis.withRedisCompatibleServer
import com.mozgobolt.core.skipIfNoDocker
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val TEST_AWAIT_TIMEOUT = 5.seconds

/**
 * The actual proof that [RedisMessageRelay] solves cross-instance fanout: two independent
 * instances, each with its own client and connections, sharing real Redis and Valkey servers —
 * not two mocked pieces asserted in isolation. `FakeMessageRelay`-backed tests (e.g.
 * `RedisRelayVehicleLocationHubTest`) cover the decorators' own logic; this proves messages cross
 * the network using both supported Redis-protocol servers.
 *
 * Docker-gated like every other real-infrastructure test in this codebase — skipped locally (no
 * Docker here), runs in CI.
 */
class RedisMessageRelayRealRedisTest {
    @BeforeTest
    fun checkDocker() {
        skipIfNoDocker()
    }

    @Test
    fun `a message published on one relay instance reaches the other on Redis and Valkey`() =
        runBlocking {
            for (server in REDIS_COMPATIBLE_SERVERS) {
                withRedisCompatibleServer(server) { _, uri ->
                    val instanceA = RedisMessageRelay(uri)
                    val instanceB = RedisMessageRelay(uri)
                    assertFalse(instanceA.isReady)
                    instanceA.start()
                    instanceB.start()
                    assertTrue(instanceA.isReady)
                    assertTrue(instanceB.isReady)

                    try {
                        val received =
                            async(start = CoroutineStart.UNDISPATCHED) {
                                withTimeout(TEST_AWAIT_TIMEOUT) { instanceB.subscribe("test-channel").first() }
                            }
                        instanceB.awaitSubscription("test-channel")

                        instanceA.publish("test-channel", "hello from instance A")

                        assertEquals("hello from instance A", received.await(), server.name)
                    } finally {
                        instanceA.stop()
                        instanceB.stop()
                        assertFalse(instanceA.isReady)
                        assertFalse(instanceB.isReady)
                    }
                }
            }
        }

    @Test
    fun `channel isolation works on Redis and Valkey`() =
        runBlocking {
            for (server in REDIS_COMPATIBLE_SERVERS) {
                withRedisCompatibleServer(server) { _, uri ->
                    val instanceA = RedisMessageRelay(uri)
                    val instanceB = RedisMessageRelay(uri)
                    instanceA.start()
                    instanceB.start()

                    try {
                        val onChannelB =
                            async(start = CoroutineStart.UNDISPATCHED) {
                                withTimeout(TEST_AWAIT_TIMEOUT) { instanceB.subscribe("channel-b").first() }
                            }
                        instanceB.awaitSubscription("channel-b")

                        instanceA.publish("channel-a", "should not reach channel-b's subscriber")
                        // A second, real publish on the channel actually being watched proves the
                        // first one didn't silently satisfy the assertion by coincidence.
                        instanceA.publish("channel-b", "the real one")

                        assertEquals("the real one", onChannelB.await(), server.name)
                    } finally {
                        instanceA.stop()
                        instanceB.stop()
                    }
                }
            }
        }
}
