/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.utility.functions

import com.mozgobolt.core.domain.AppResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CoroutineUtilityFunctionsTest {
    @Test
    fun `sync catching returns success`() {
        assertEquals(AppResult.Success(42), runSyncCatching { 42 })
    }

    @Test
    fun `sync catching maps regular failures and runs its finalizer`() {
        var finalized = false
        val result =
            runSyncCatching(
                mapException = { it.message ?: "unknown" },
                onFinally = { finalized = true },
            ) {
                error("boom")
            }

        assertEquals(AppResult.Error("boom"), result)
        assertTrue(finalized)
    }

    @Test
    fun `sync catching propagates cancellation and fatal errors`() {
        val cancellation = CancellationException("cancelled")
        val fatalError = AssertionError("fatal")

        assertSame(
            cancellation,
            assertFailsWith<CancellationException> { runSyncCatching { throw cancellation } },
        )
        assertSame(fatalError, assertFailsWith<AssertionError> { runSyncCatching { throw fatalError } })
    }

    @Test
    fun `suspend catching returns success`() =
        runBlocking {
            assertEquals(AppResult.Success(42), runSuspendCatching { 42 })
        }

    @Test
    fun `suspend catching maps regular failures`() =
        runBlocking {
            val result =
                runSuspendCatching(mapException = { it.message ?: "unknown" }) {
                    error("boom")
                }

            assertEquals(AppResult.Error("boom"), result)
        }

    @Test
    fun `suspend catching propagates cancellation and fatal errors`() {
        val cancellation = CancellationException("cancelled")
        val fatalError = AssertionError("fatal")

        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                runBlocking { runSuspendCatching { throw cancellation } }
            },
        )
        assertSame(
            fatalError,
            assertFailsWith<AssertionError> {
                runBlocking { runSuspendCatching { throw fatalError } }
            },
        )
    }

    @Test
    fun `cancelling the caller while suspended still cancels the job`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val job =
                launch {
                    runSuspendCatching {
                        started.complete(Unit)
                        delay(10_000)
                    }
                }

            started.await()
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertFalse(job.isActive)
        }

    @Test
    fun `cancellation after the block returns is still propagated`() =
        runBlocking {
            val job =
                launch {
                    runSuspendCatching {
                        currentCoroutineContext().cancel()
                        42
                    }
                }

            job.join()

            assertTrue(job.isCancelled)
        }

    @Test
    fun `cancellation handler maps block cancellation while caller is active`() =
        runBlocking {
            val cancellation = CancellationException("block cancelled")
            val result =
                runSuspendCatchingWithCancellationHandler(
                    mapException = { it },
                    onCancellation = { AppResult.Error(it) },
                ) {
                    throw cancellation
                }

            assertSame(cancellation, result.errorOrNull())
        }

    @Test
    fun `cancellation handler cannot map caller cancellation`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            var handlerCalled = false
            val job =
                launch {
                    runSuspendCatchingWithCancellationHandler(
                        mapException = { it },
                        onCancellation = {
                            handlerCalled = true
                            AppResult.Error(it)
                        },
                    ) {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }

            started.await()
            job.cancelAndJoin()

            assertFalse(handlerCalled)
            assertTrue(job.isCancelled)
        }

    @Test
    fun `runWhileActive rejects a result produced after cancellation`() =
        runBlocking {
            var returned = false
            val job =
                launch {
                    returned =
                        runWhileActive {
                            currentCoroutineContext().cancel()
                            42
                        } == 42
                }

            job.join()

            assertFalse(returned)
            assertTrue(job.isCancelled)
        }

    @Test
    fun `finalizer failure does not replace cancellation`() {
        val cancellation = CancellationException("cancelled")
        val cleanupFailure = IllegalStateException("cleanup failed")
        var finalized = false

        val propagated =
            assertFailsWith<CancellationException> {
                runBlocking {
                    runSuspendCatching(
                        onFinally = {
                            finalized = true
                            throw cleanupFailure
                        },
                    ) {
                        throw cancellation
                    }
                }
            }

        assertSame(cancellation, propagated)
        assertSame(cleanupFailure, propagated.suppressed.single())
        assertTrue(finalized)
    }

    @Test
    fun `finalizer failure propagates after a successful block`() {
        val cleanupFailure = IllegalStateException("cleanup failed")

        val propagated =
            assertFailsWith<IllegalStateException> {
                runBlocking {
                    runSuspendCatching(
                        onFinally = { throw cleanupFailure },
                    ) {
                        42
                    }
                }
            }

        assertSame(cleanupFailure, propagated)
    }
}
