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

    /** Invokes the matching suspend handler and returns its result. */
    suspend fun <R> foldSuspend(
        onSuccess: suspend (value: T) -> R,
        onError: suspend (error: E) -> R,
    ): R =
        when (this) {
            is Success -> onSuccess(data)
            is Error -> onError(errorType)
        }

    /** Transforms a success value while leaving errors unchanged. */
    inline fun <NewT> map(transform: (value: T) -> NewT): AppResult<NewT, E> =
        when (this) {
            is Success -> Success(transform(data))
            is Error -> Error(errorType)
        }

    /** Transforms an error while leaving success values unchanged. */
    inline fun <NewE> mapError(transform: (error: E) -> NewE): AppResult<T, NewE> =
        when (this) {
            is Success -> Success(data)
            is Error -> Error(transform(errorType))
        }

    /**
     * Chains a success value to another result while returning errors unchanged.
     *
     * The callback runs only for [Success], so it cannot replace an existing [Error].
     */
    inline fun <NewT> flatMap(transform: (value: T) -> AppResult<NewT, @UnsafeVariance E>): AppResult<NewT, E> =
        when (this) {
            is Success -> transform(data)
            is Error -> Error(errorType)
        }

    /** Runs [action] for a success value and returns this result unchanged. */
    inline fun onSuccess(action: (value: T) -> Unit): AppResult<T, E> {
        if (this is Success) action(data)
        return this
    }

    /** Runs [action] for an error and returns this result unchanged. */
    inline fun onError(action: (error: E) -> Unit): AppResult<T, E> {
        if (this is Error) action(errorType)
        return this
    }

    /** Returns the success value, or `null` for an error. */
    fun getOrNull(): T? =
        when (this) {
            is Success -> data
            is Error -> null
        }

    /** Returns the error, or `null` for a success. */
    fun errorOrNull(): E? =
        when (this) {
            is Success -> null
            is Error -> errorType
        }

    /** Returns the success value or computes a fallback from its error. */
    inline fun getOrElse(onError: (error: E) -> @UnsafeVariance T): T =
        when (this) {
            is Success -> data
            is Error -> onError(errorType)
        }

    /** Returns the success value or throws the exception produced from its error. */
    inline fun getOrThrow(exceptionFromError: (error: E) -> Throwable): T =
        when (this) {
            is Success -> data
            is Error -> throw exceptionFromError(errorType)
        }
}
