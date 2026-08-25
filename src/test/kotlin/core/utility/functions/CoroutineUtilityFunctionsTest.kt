package com.shelflife.core.utility.functions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoroutineUtilityFunctionsTest {
    @Test
    fun `a successful block returns Result success`() {
        val result = runSuspendCatching { 42 }

        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `a regular exception is caught as Result failure`() {
        val result = runSuspendCatching { error("boom") }

        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a CancellationException is rethrown, not swallowed into Result failure`() {
        assertFailsWith<CancellationException> {
            runSuspendCatching { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `cancelling the enclosing coroutine while suspended inside the block still cancels the job`() =
        runBlocking {
            val job = launch { runSuspendCatching { delay(10_000) } }

            job.cancel()
            job.join()

            assertTrue(job.isCancelled)
            assertFalse(job.isActive)
        }
}
