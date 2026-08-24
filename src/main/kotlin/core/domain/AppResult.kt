package com.besa.shelflife.core.domain

sealed class AppResult<out T, out E> {
    data class Success<out T>(val data: T) : AppResult<T, Nothing>()
    data class Error<out E>(val errorType: E) : AppResult<Nothing, E>()

    inline fun <R> fold(
        onSuccess: (value: T) -> R,
        onError: (error: E) -> R
    ): R = when (this) {
        is Success -> onSuccess(data)
        is Error -> onError(errorType)
    }
}