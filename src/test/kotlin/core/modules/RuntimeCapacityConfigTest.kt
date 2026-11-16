/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.modules

import kotlin.test.Test
import kotlin.test.assertTrue

class RuntimeCapacityConfigTest {
    @Test
    fun `CPU-bound work never defaults to every core so request handling keeps some`() {
        val cores = Runtime.getRuntime().availableProcessors()
        val config = RuntimeCapacityConfig()

        listOf(config.passwordHashConcurrency, config.imageProcessingConcurrency).forEach { concurrency ->
            assertTrue(concurrency >= 1, "at least one operation must be able to run")
            if (cores >
                1
            ) {
                assertTrue(
                    concurrency < cores,
                    "$concurrency of $cores cores leaves nothing for the rest of Dispatchers.Default",
                )
            }
        }
    }
}
