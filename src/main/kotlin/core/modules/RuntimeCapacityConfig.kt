/*
 * SPDX-License-Identifier: Apache-2.0
 */

// These fields deserialize trusted deployment configuration; this is not an API response model.
@file:Suppress("KotlinxSerializationSensitiveField")

package com.mozgobolt.core.modules

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable

/**
 * Per-instance resource limits. These are starting points, not throughput guarantees.
 *
 * HikariCP gives `((databaseCpuCores * 2) + effectiveSpindleCount)` as an initial heuristic, then
 * recommends testing nearby sizes. Database resources, storage behavior, replica count, and server
 * connection limits are deployment-specific, so the pool size stays configurable.
 *
 * Password hash concurrency defaults to available CPU cores. Each current Argon2 operation uses
 * about 64 MiB; image processing and hashing can also overlap, so tune both against memory.
 *
 * @see <a href="https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing">HikariCP pool sizing</a>
 */
@Serializable
data class RuntimeCapacityConfig(
    /** Maximum simultaneous Hikari connections for this backend process. */
    val databasePoolSize: Int = DEFAULT_DATABASE_POOL_SIZE,
    /** Maximum simultaneous password-hashing operations for this backend process. */
    val passwordHashConcurrency: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
    /** Maximum simultaneous CPU-bound image-processing operations for this backend process. */
    val imageProcessingConcurrency: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
) {
    /** Shared defaults for runtime-capacity configuration. */
    companion object {
        /** HikariCP's default maximum pool size. */
        const val DEFAULT_DATABASE_POOL_SIZE = 10
    }
}

/**
 * Creates dispatcher views once per application instance. Database pool limits are also enforced
 * by Hikari; dispatcher parallelism is not a permit held for the lifetime of a suspended transaction.
 */
@Suppress("InjectDispatcher") // Dispatchers are created at the application infrastructure boundary.
fun RuntimeCapacityConfig.createDispatchers(): RuntimeExecutionDispatchers =
    RuntimeExecutionDispatchers(
        database = Dispatchers.IO.limitedParallelism(databasePoolSize),
        passwordHashing = Dispatchers.Default.limitedParallelism(passwordHashConcurrency),
        imageProcessing = Dispatchers.Default.limitedParallelism(imageProcessingConcurrency),
    )

/** Dispatcher views created from [RuntimeCapacityConfig] for one application instance. */
data class RuntimeExecutionDispatchers(
    /** Dispatcher for blocking JDBC work. */
    val database: CoroutineDispatcher,
    /** Dispatcher view for CPU- and memory-intensive Argon2 operations. */
    val passwordHashing: CoroutineDispatcher,
    /** Dispatcher view for CPU- and memory-intensive image processing. */
    val imageProcessing: CoroutineDispatcher,
)
