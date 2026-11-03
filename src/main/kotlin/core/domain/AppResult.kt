/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.domain

/** Represents either a successful value or an expected domain error. */
sealed class AppResult<out T, out E> {
    /** A completed operation containing [data]. */
    data class Success<out T>(
        /** The value produced by the operation. */
        val data: T,
    ) : AppResult<T, Nothing>()

    /** An operation that ended with the expected [errorType]. */
    data class Error<out E>(
        /** The domain error describing why the operation did not succeed. */
        val errorType: E,
    ) : AppResult<Nothing, E>()

    /** Invokes the matching handler and returns its result. */
    inline fun <R> fold(
        onSuccess: (value: T) -> R,
        onError: (error: E) -> R,
    ): R =
        when (this) {
            is Success -> onSuccess(data)
            is Error -> onError(errorType)
        }
}
