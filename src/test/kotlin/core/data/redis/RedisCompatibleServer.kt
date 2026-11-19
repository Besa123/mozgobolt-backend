/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.redis

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

internal data class RedisCompatibleServer(
    val name: String,
    val image: String,
    val cliCommand: String,
)

internal val REDIS_COMPATIBLE_SERVERS =
    listOf(
        RedisCompatibleServer("Redis 8.10.3", "redis:8.10.3-alpine", "redis-cli"),
        RedisCompatibleServer("Valkey 9.2.0", "valkey/valkey:9.2.0-alpine", "valkey-cli"),
    )

internal suspend fun <T> withRedisCompatibleServer(
    server: RedisCompatibleServer,
    block: suspend CoroutineScope.(container: GenericContainer<*>, uri: String) -> T,
): T {
    val container =
        GenericContainer(DockerImageName.parse(server.image))
            .withExposedPorts(6379)
    container.start()

    return try {
        coroutineScope {
            block(container, "redis://${container.host}:${container.getMappedPort(6379)}")
        }
    } finally {
        container.stop()
    }
}
