/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.ratelimit

import com.mozgobolt.core.data.redis.REDIS_COMPATIBLE_SERVERS
import com.mozgobolt.core.data.redis.withRedisCompatibleServer
import com.mozgobolt.core.skipIfNoDocker
import io.ktor.server.plugins.ratelimit.RateLimiter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RedisRateLimitStoreRealRedisCompatibleTest {
    @Test
    fun `independent application stores share atomic buckets on Redis and Valkey`() =
        runBlocking<Unit> {
            skipIfNoDocker()

            for (server in REDIS_COMPATIBLE_SERVERS) {
                withRedisCompatibleServer(server) { container, uri ->
                    val storeA = RedisRateLimitStore(uri, "test-issuer:test-audience", "shared-test-key-secret")
                    val storeB = RedisRateLimitStore(uri, "test-issuer:test-audience", "shared-test-key-secret")

                    try {
                        storeA.start()
                        storeB.start()
                        storeA.ping()
                        storeB.ping()

                        val limiterA =
                            storeA.rateLimiter("auth-limit", "198.51.100.10", limit = 5, refillPeriod = 60.seconds)
                        val limiterB =
                            storeB.rateLimiter("auth-limit", "198.51.100.10", limit = 5, refillPeriod = 60.seconds)
                        val results =
                            coroutineScope {
                                (0 until 200)
                                    .map { index ->
                                        async(Dispatchers.IO) {
                                            if (index % 2 == 0) {
                                                limiterA.tryConsume()
                                            } else {
                                                limiterB.tryConsume()
                                            }
                                        }
                                    }.awaitAll()
                            }

                        assertEquals(5, results.count { it is RateLimiter.State.Available }, server.name)
                        assertEquals(195, results.count { it is RateLimiter.State.Exhausted }, server.name)
                        assertIs<RateLimiter.State.Available>(
                            storeB.rateLimiter("auth-limit", "198.51.100.11", 5, 60.seconds).tryConsume(),
                            "a different source IP has a separate bucket on ${server.name}",
                        )
                        assertIs<RateLimiter.State.Available>(
                            storeB.rateLimiter("api-limit", "198.51.100.10", 5, 60.seconds).tryConsume(),
                            "a different limiter has a separate bucket on ${server.name}",
                        )
                        assertFalse(
                            container
                                .execInContainer(server.cliCommand, "--raw", "KEYS", "mozgobolt:rate-limits:v1:*")
                                .stdout
                                .contains("198.51.100.10"),
                            "Redis keys must not contain raw source IPs on ${server.name}",
                        )

                        val shortWindowA = storeA.rateLimiter("refill-test", "client", 1, 150.milliseconds)
                        val shortWindowB = storeB.rateLimiter("refill-test", "client", 1, 150.milliseconds)
                        assertIs<RateLimiter.State.Available>(shortWindowA.tryConsume())
                        assertIs<RateLimiter.State.Exhausted>(shortWindowB.tryConsume())
                        delay(300.milliseconds)
                        assertIs<RateLimiter.State.Available>(shortWindowB.tryConsume())
                    } finally {
                        storeA.stop()
                        storeB.stop()
                    }
                }
            }
        }
}
