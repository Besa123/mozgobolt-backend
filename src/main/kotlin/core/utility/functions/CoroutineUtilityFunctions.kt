/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.utility.functions

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Checks caller cancellation before [block] starts and before its result is returned. This cannot
 * roll back side effects [block] already completed.
 */
suspend fun <T> runWhileActive(block: suspend () -> T): T {
    currentCoroutineContext().ensureActive()
    val value = block()
    currentCoroutineContext().ensureActive()
    return value
}
