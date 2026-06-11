package com.besa.boardShare.core.domain

sealed class AppResult<out T, out E> {
    data class Success<out T>(val data: T) : AppResult<T, Nothing>()
    data class Error<out E>(val errorType: E) : AppResult<Nothing, E>()
}