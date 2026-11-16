/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.domain

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class AppResultTest {
    @Test
    fun `map and flatMap transform successes without changing the error type`() {
        val result: AppResult<Int, String> = AppResult.Success(20)

        assertEquals(
            AppResult.Success(42),
            result.map { it * 2 }.flatMap { AppResult.Success(it + 2) },
        )
    }

    @Test
    fun `map and mapError leave the other branch unchanged`() {
        val error: AppResult<Int, String> = AppResult.Error("failed")

        assertEquals(AppResult.Error("failed"), error.map { it * 2 })
        assertEquals(AppResult.Error("FAILED"), error.mapError { it.uppercase() })
    }

    @Test
    fun `branch handlers and extraction select the matching outcome`() {
        var successValue: Int? = null
        var errorValue: String? = null
        val success: AppResult<Int, String> = AppResult.Success(42)
        val error: AppResult<Int, String> = AppResult.Error("failed")

        success.onSuccess { successValue = it }.onError { errorValue = it }
        error.onSuccess { successValue = it }.onError { errorValue = it }

        assertEquals(42, successValue)
        assertEquals("failed", errorValue)
        assertEquals(42, success.getOrNull())
        assertEquals("failed", error.errorOrNull())
        assertEquals(42, error.getOrElse { 42 })
    }

    @Test
    fun `getOrThrow converts an error only when requested`() {
        val error: AppResult<Int, String> = AppResult.Error("failed")

        assertFailsWith<IllegalStateException> {
            error.getOrThrow(::IllegalStateException)
        }
    }

    @Test
    fun `suspend fold invokes the matching branch`() =
        runBlocking {
            val success: AppResult<Int, String> = AppResult.Success(21)
            val error: AppResult<Int, String> = AppResult.Error("failed")

            assertEquals(
                "42",
                success.foldSuspend(
                    onSuccess = { (it * 2).toString() },
                    onError = { "unexpected: $it" },
                ),
            )
            assertEquals(
                "failed",
                error.foldSuspend(
                    onSuccess = { "unexpected: $it" },
                    onError = { it },
                ),
            )
        }

    @Test
    fun `getOrThrow converts throwable errors`() {
        val failure = IllegalArgumentException("failed")
        val error: AppResult<Int, Throwable> = AppResult.Error(failure)

        val propagated = assertFailsWith<IllegalArgumentException> { error.getOrThrow { it } }

        assertSame(failure, propagated)
    }
}
