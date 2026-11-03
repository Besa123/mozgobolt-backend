/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.utility

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

/**
 * Parent scope for application-lifetime background tasks.
 *
 * The default dispatcher is IO for long-lived server workers; CPU/in-memory work should override it.
 * Request-scoped work should use structured concurrency instead of this scope.
 */
class ApplicationBackgroundScope(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CoroutineScope {
    private val job = SupervisorJob()
    private val exceptionHandler =
        CoroutineExceptionHandler { context, failure ->
            val taskName = context[CoroutineName]?.name ?: "unnamed"
            logger.error(failure) { "Uncaught application background task failure (task=$taskName)" }
        }

    override val coroutineContext: CoroutineContext =
        job + dispatcher + exceptionHandler + CoroutineName("application-background")

    /** Cancels all application-lifetime tasks owned by this scope. */
    fun cancel() {
        job.cancel()
    }
}
