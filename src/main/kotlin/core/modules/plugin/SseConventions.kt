/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.modules.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Shared tuning/conventions for every long-lived SSE route (`GET .../live` endpoints) in this
 * app, so unrelated features don't each reinvent slightly different heartbeat/reconnect timing
 * or event names for the same generic SSE lifecycle concerns.
 */
val SSE_HEARTBEAT_PERIOD = 30.seconds
val RECONNECT_WARNING_LEAD_TIME = 60.seconds

/** Sent shortly before the caller's JWT expires, so the client knows to reconnect with a fresh
 * token rather than silently losing the stream. */
const val SSE_EVENT_RECONNECT = "reconnect"

/**
 * Runs [collect] (an SSE route's main event-collection loop) alongside the conventional
 * reconnect-warning timer, and always cancels that timer once [collect] returns — by any route,
 * whether it ran to its own timeout or ended early because
 * [com.mozgobolt.core.utility.functions.collectWhileAccessSessionActive] detected a revoked
 * session. Every `GET .../live` route schedules this timer with a bare `launch { }` alongside its
 * collection loop; without an explicit cancel, that timer is an un-awaited sibling coroutine that
 * keeps the whole handler's coroutine scope — and so the underlying connection — alive until it
 * fires on its own, up to [remainingTokenValidity] minus [RECONNECT_WARNING_LEAD_TIME] later, even
 * though the collection loop the client actually cares about already ended.
 */
suspend fun runSseCollectionWithReconnectWarning(
    remainingTokenValidity: Duration,
    sendReconnectEvent: suspend () -> Unit,
    collect: suspend CoroutineScope.() -> Unit,
) {
    coroutineScope {
        val reconnectWarning =
            launch {
                delay((remainingTokenValidity - RECONNECT_WARNING_LEAD_TIME).coerceAtLeast(Duration.ZERO))
                sendReconnectEvent()
            }
        try {
            collect()
        } finally {
            reconnectWarning.cancel()
        }
    }
}
