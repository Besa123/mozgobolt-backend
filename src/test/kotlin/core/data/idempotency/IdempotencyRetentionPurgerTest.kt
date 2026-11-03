/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.idempotency

import com.mozgobolt.core.utility.ApplicationBackgroundScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

class IdempotencyRetentionPurgerTest {
    private class RecordingStore : IdempotencyStore by FakeIdempotencyStore() {
        val purgeTimes = mutableListOf<Instant>()
        var attemptCount = 0
            private set
        var throwOnNextPurge: Throwable? = null
        var deletedCountToReturn = 0

        override suspend fun purgeExpiredRecords(now: Instant): Int {
            attemptCount++
            throwOnNextPurge?.let {
                throwOnNextPurge = null
                throw it
            }
            purgeTimes += now
            return deletedCountToReturn
        }
    }

    private suspend fun pollUntil(
        timeoutMs: Long = 3000,
        intervalMs: Long = 15,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(intervalMs)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    @Test
    fun `purge delegates to the store using the injected clock`() =
        runBlocking {
            val now = Instant.parse("2026-11-04T12:00:00Z")
            val store = RecordingStore().apply { deletedCountToReturn = 3 }
            val purger =
                IdempotencyRetentionPurger(
                    store = store,
                    backgroundScope = ApplicationBackgroundScope(),
                    clock = Clock.fixed(now, ZoneOffset.UTC),
                )

            assertEquals(3, purger.purge())
            assertEquals(listOf(now), store.purgeTimes)
        }

    @Test
    fun `a failed purge does not stop future cleanup attempts`() =
        runBlocking {
            val store = RecordingStore().apply { throwOnNextPurge = IOException("simulated transient DB failure") }
            val purger =
                IdempotencyRetentionPurger(
                    store = store,
                    backgroundScope = ApplicationBackgroundScope(),
                    checkInterval = 20.milliseconds,
                )

            try {
                purger.start()
                pollUntil { store.attemptCount >= 1 }
                pollUntil { store.purgeTimes.isNotEmpty() }
            } finally {
                purger.stop()
            }
        }

    @Test
    fun `stop halts future automatic purges`() =
        runBlocking {
            val store = RecordingStore()
            val purger =
                IdempotencyRetentionPurger(
                    store = store,
                    backgroundScope = ApplicationBackgroundScope(),
                    checkInterval = 20.milliseconds,
                )
            purger.start()
            pollUntil { store.attemptCount >= 1 }

            purger.stop()
            val countAtStop = store.attemptCount
            delay(150)

            assertEquals(countAtStop, store.attemptCount)
        }
}
