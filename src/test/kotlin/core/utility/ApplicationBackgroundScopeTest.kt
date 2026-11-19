/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.utility

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ApplicationBackgroundScopeTest {
    @Test
    fun `cancelAndJoin waits for every application background task to finish cleanup`() =
        runBlocking {
            val backgroundScope = ApplicationBackgroundScope()
            val firstStarted = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val firstCancelled = CompletableDeferred<Unit>()
            val secondCancelled = CompletableDeferred<Unit>()
            val jobs =
                listOf(
                    backgroundScope.launch(Dispatchers.IO + CoroutineName("first-test-task")) {
                        firstStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            firstCancelled.complete(Unit)
                        }
                    },
                    backgroundScope.launch(Dispatchers.Default + CoroutineName("second-test-task")) {
                        secondStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            secondCancelled.complete(Unit)
                        }
                    },
                )

            try {
                withTimeout(2.seconds) { awaitAll(firstStarted, secondStarted) }

                withTimeout(2.seconds) { backgroundScope.cancelAndJoin() }

                assertTrue(firstCancelled.isCompleted)
                assertTrue(secondCancelled.isCompleted)
                assertTrue(jobs.all { it.isCancelled })
            } finally {
                backgroundScope.cancel()
            }
        }
}
