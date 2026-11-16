/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.utility.functions

import com.mozgobolt.core.domain.AppResult
import kotlinx.coroutines.CancellationException

/**
 * Captures non-fatal failures from a synchronous boundary as an [AppResult]. Cancellation and fatal errors propagate.
 * [onFinally] always runs; if it also fails during cancellation or a fatal error, its failure is suppressed.
 */
fun <T> runSyncCatching(
    onFinally: () -> Unit = {},
    block: () -> T,
): AppResult<T, Throwable> =
    runSyncCatching(
        mapException = { it },
        onFinally = onFinally,
        block = block,
    )

/** Like [runSyncCatching], but maps a captured failure into the caller's error type. */
fun <T, E> runSyncCatching(
    mapException: (Throwable) -> E,
    onFinally: () -> Unit = {},
    block: () -> T,
): AppResult<T, E> =
    captureSyncResult(
        mapException = mapException,
        onFinally = onFinally,
        block = block,
    )

@Suppress("TooGenericExceptionCaught")
private fun <T, E> captureSyncResult(
    mapException: (Throwable) -> E,
    onFinally: () -> Unit,
    block: () -> T,
): AppResult<T, E> {
    var thrownFailure: Throwable? = null
    try {
        return captureSyncBlock(mapException, block)
    } catch (failure: Throwable) {
        thrownFailure = failure
        throw failure
    } finally {
        runFinally(onFinally, thrownFailure)
    }
}

@Suppress("TooGenericExceptionCaught")
private fun <T, E> captureSyncBlock(
    mapException: (Throwable) -> E,
    block: () -> T,
): AppResult<T, E> =
    try {
        AppResult.Success(block())
    } catch (cancellation: CancellationException) {
        rethrowUncapturable(cancellation)
    } catch (fatalError: Error) {
        rethrowUncapturable(fatalError)
    } catch (throwable: Throwable) {
        AppResult.Error(mapException(throwable))
    }
