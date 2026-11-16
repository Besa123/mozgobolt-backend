/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.modules.plugin

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Regression guard for a bug a security probe suite found against the real app: every `GET .../live`
 * route scheduled its reconnect-warning timer with a bare `launch { }` alongside its collection loop.
 * When the collection loop ended early (the access token's session family was revoked mid-stream),
 * that timer — still scheduled for up to [RECONNECT_WARNING_LEAD_TIME] before the token's own
 * ~15-minute expiry — was never cancelled, so the handler's coroutine scope, and the SSE connection
 * with it, stayed open regardless: a revoked buyer kept receiving live vehicle locations for up to
 * fifteen more minutes. [runSseCollectionWithReconnectWarning] must always cancel that timer once
 * the collection loop returns, by any path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SseConventionsTest {
    @Test
    fun `the reconnect timer is cancelled, and never fires, when the collection loop ends well before it`() =
        runTest {
            var reconnectEventsSent = 0

            runSseCollectionWithReconnectWarning(
                remainingTokenValidity = 15.minutes,
                sendReconnectEvent = { reconnectEventsSent++ },
            ) {
                // The collection loop itself ends almost immediately, long before the reconnect
                // timer (due at 15min - 60s) would ever fire.
            }

            // runTest's virtual clock would have let a still-running (uncancelled) delay complete;
            // reaching this line at all, with no reconnect event, is the regression guard.
            assertEquals(0, reconnectEventsSent)
        }

    @Test
    fun `the reconnect event still fires when the collection loop genuinely outlives the lead time`() =
        runTest {
            var reconnectEventsSent = 0

            runSseCollectionWithReconnectWarning(
                remainingTokenValidity = 90.seconds,
                sendReconnectEvent = { reconnectEventsSent++ },
            ) {
                delay(90.seconds)
            }

            assertEquals(1, reconnectEventsSent)
        }
}
