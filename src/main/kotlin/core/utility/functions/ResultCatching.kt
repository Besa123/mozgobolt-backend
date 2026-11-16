/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.utility.functions

import com.mozgobolt.core.domain.AppResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Captures non-fatal failures from a suspending boundary as an [AppResult] while propagating
 * cancellation of the caller's coroutine. Caller cancellation is checked before and after [block].
 * [onFinally] always runs; if it also fails during cancellation or a fatal error, its failure is suppressed.
 */
suspend fun <T> runSuspendCatching(
    onFinally: () -> Unit = {},
    block: suspend () -> T,
): AppResult<T, Throwable> =
    runSuspendCatching(
        mapException = { it },
        onFinally = onFinally,
        block = block,
    )

/** Like [runSuspendCatching], but maps a captured failure into the caller's error type. */
suspend fun <T, E> runSuspendCatching(
    mapException: (Throwable) -> E,
    onFinally: () -> Unit = {},
    block: suspend () -> T,
): AppResult<T, E> =
    captureSuspendResult(
        mapException = mapException,
        onFinally = onFinally,
        block = block,
    )

/**
 * Maps cancellation thrown by [block] only when the caller's coroutine remains active.
 * Cancellation of the caller always propagates.
 */
suspend fun <T, E> runSuspendCatchingWithCancellationHandler(
    mapException: (Throwable) -> E,
    onCancellation: (CancellationException) -> AppResult<T, E>,
    block: suspend () -> T,
): AppResult<T, E> =
    captureSuspendResult(
        mapException = mapException,
        onFinally = {},
        onCancellation = onCancellation,
        block = block,
    )

@Suppress("TooGenericExceptionCaught")
private suspend fun <T, E> captureSuspendResult(
    mapException: (Throwable) -> E,
    onFinally: () -> Unit,
    onCancellation: (CancellationException) -> AppResult<T, E> = { throw it },
    block: suspend () -> T,
): AppResult<T, E> {
    var thrownFailure: Throwable? = null
    try {
        return captureSuspendBlock(mapException, onCancellation, block)
    } catch (failure: Throwable) {
        thrownFailure = failure
        throw failure
    } finally {
        runFinally(onFinally, thrownFailure)
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun <T, E> captureSuspendBlock(
    mapException: (Throwable) -> E,
    onCancellation: (CancellationException) -> AppResult<T, E>,
    block: suspend () -> T,
): AppResult<T, E> =
    try {
        currentCoroutineContext().ensureActive()
        val value = block()
        currentCoroutineContext().ensureActive()
        AppResult.Success(value)
    } catch (cancellation: CancellationException) {
        currentCoroutineContext().ensureActive()
        val result = onCancellation(cancellation)
        currentCoroutineContext().ensureActive()
        result
    } catch (fatalError: Error) {
        rethrowUncapturable(fatalError)
    } catch (throwable: Throwable) {
        currentCoroutineContext().ensureActive()
        AppResult.Error(mapException(throwable))
    }

@Suppress("TooGenericExceptionCaught")
internal fun runFinally(
    onFinally: () -> Unit,
    precedingFailure: Throwable?,
) {
    try {
        onFinally()
    } catch (cleanupFailure: Throwable) {
        if (precedingFailure is CancellationException || precedingFailure is Error) {
            precedingFailure.addSuppressedIfDistinct(cleanupFailure)
        } else {
            throw cleanupFailure
        }
    }
}

internal fun rethrowUncapturable(failure: Throwable): Nothing = throw failure

private fun Throwable.addSuppressedIfDistinct(failure: Throwable) {
    if (this !== failure) addSuppressed(failure)
}
