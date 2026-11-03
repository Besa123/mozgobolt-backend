/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.utility.functions

import com.mozgobolt.core.domain.security.AccessTokenSessionValidator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AccessTokenSessionStreamGuardTest {
    @Test
    fun revokedSessionStopsAnAlreadyOpenStreamAtTheNextCheck() =
        runTest {
            var active = true
            val validator = AccessTokenSessionValidator { _, _ -> active }
            val received = mutableListOf<Int>()
            val started = CompletableDeferred<Unit>()
            val stream =
                flow {
                    emit(1)
                    started.complete(Unit)
                    awaitCancellation()
                }

            val collector =
                launch {
                    stream.collectWhileAccessSessionActive(
                        userId = 7,
                        familyId = "family-7",
                        validator = validator,
                        recheckInterval = Duration.ofSeconds(1),
                    ) { received += it }
                }
            runCurrent()
            started.await()
            active = false
            advanceTimeBy(1_000)
            runCurrent()

            collector.join()

            assertEquals(listOf(1), received)
        }
}
