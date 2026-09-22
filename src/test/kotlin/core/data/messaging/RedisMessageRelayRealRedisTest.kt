package com.mozgobolt.core.data.messaging

import com.mozgobolt.core.skipIfNoDocker
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val TEST_AWAIT_TIMEOUT = 5.seconds

// Real Redis's SUBSCRIBE is acknowledged asynchronously — there's no portable "wait until the
// server has registered this subscription" call in Lettuce's reactive pub/sub API. This is a
// generous grace period for a loopback Testcontainers connection, not a tuned production value;
// it only needs to outlast one local network round trip.
private val SUBSCRIBE_SETTLE_DELAY = 300.milliseconds

/**
 * The actual proof that [RedisMessageRelay] solves cross-instance fanout: two independent
 * instances, each with its own client and connections, sharing one real Redis — not two mocked
 * pieces asserted in isolation. `FakeMessageRelay`-backed tests (e.g.
 * `RedisRelayVehicleLocationHubTest`) cover the decorators' own logic; this covers the one thing
 * only a real Redis can prove: that a message published on one instance's connection is actually
 * delivered to a different instance's connection over the network.
 *
 * Docker-gated like every other real-infrastructure test in this codebase — skipped locally (no
 * Docker here), runs in CI.
 */
class RedisMessageRelayRealRedisTest {
    @BeforeTest
    fun checkDocker() = skipIfNoDocker()

    @Test
    fun `a message published on one relay instance is received by another sharing the same Redis`() =
        runBlocking {
            val redis = GenericContainer(DockerImageName.parse("valkey/valkey:8-alpine")).withExposedPorts(6379)
            redis.start()

            try {
                val uri = "redis://${redis.host}:${redis.getMappedPort(6379)}"
                val instanceA = RedisMessageRelay(uri)
                val instanceB = RedisMessageRelay(uri)
                instanceA.start()
                instanceB.start()

                try {
                    val received =
                        async { withTimeout(TEST_AWAIT_TIMEOUT) { instanceB.subscribe("test-channel").first() } }
                    delay(SUBSCRIBE_SETTLE_DELAY)

                    instanceA.publish("test-channel", "hello from instance A")

                    assertEquals("hello from instance A", received.await())
                } finally {
                    instanceA.stop()
                    instanceB.stop()
                }
            } finally {
                redis.stop()
            }
        }

    @Test
    fun `a message on a different channel is never received`() =
        runBlocking {
            val redis = GenericContainer(DockerImageName.parse("valkey/valkey:8-alpine")).withExposedPorts(6379)
            redis.start()

            try {
                val uri = "redis://${redis.host}:${redis.getMappedPort(6379)}"
                val instanceA = RedisMessageRelay(uri)
                val instanceB = RedisMessageRelay(uri)
                instanceA.start()
                instanceB.start()

                try {
                    val onChannelB =
                        async { withTimeout(TEST_AWAIT_TIMEOUT) { instanceB.subscribe("channel-b").first() } }
                    delay(SUBSCRIBE_SETTLE_DELAY)

                    instanceA.publish("channel-a", "should not reach channel-b's subscriber")
                    // A second, real publish on the channel actually being watched proves the
                    // first one didn't silently satisfy the assertion by coincidence.
                    instanceA.publish("channel-b", "the real one")

                    assertEquals("the real one", onChannelB.await())
                } finally {
                    instanceA.stop()
                    instanceB.stop()
                }
            } finally {
                redis.stop()
            }
        }
}
