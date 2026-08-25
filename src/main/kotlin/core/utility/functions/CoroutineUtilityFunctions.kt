package com.besa.shelflife.core.utility.functions

import kotlin.coroutines.cancellation.CancellationException

inline fun <R> runSuspendCatching(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (cancellationException: CancellationException) {
        throw cancellationException
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }
