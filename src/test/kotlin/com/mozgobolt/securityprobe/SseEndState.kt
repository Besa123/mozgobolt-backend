/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.securityprobe

import com.mozgobolt.core.utility.functions.runSuspendCatching
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/** Whether a live-map SSE connection was still receiving frames when the probe stopped watching it. */
internal enum class SseEndState { STILL_OPEN, CLOSED }

/**
 * Opens `GET /vehicle-tracking/live`, runs [whileConnected] once the handshake has completed (by
 * which point the server has already registered its nearby subscription — `openNearby` registers
 * before handing back control, so there is no race to wait out here), then reports whether the
 * stream was still open [watchFor] later.
 */
internal suspend fun watchLiveMap(
    http: HttpClient,
    baseUrl: String,
    accessToken: String,
    latitude: Double,
    longitude: Double,
    radiusKm: Double,
    watchFor: Duration,
    whileConnected: suspend () -> Unit,
): SseEndState =
    coroutineScope {
        val connected = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Unit>()

        val collector =
            launch {
                runSuspendCatching(
                    onFinally = { ended.complete(Unit) },
                ) {
                    http.sse(
                        request = {
                            url("$baseUrl/api/v1/vehicle-tracking/live")
                            url.parameters.append("scope", "nearby")
                            url.parameters.append("lat", latitude.toString())
                            url.parameters.append("lon", longitude.toString())
                            url.parameters.append("radiusKm", radiusKm.toString())
                            bearerAuth(accessToken)
                        },
                    ) {
                        connected.complete(Unit)
                        incoming.collect { }
                    }
                }
            }

        withTimeoutOrNull(watchFor + watchFor) { connected.await() }
        whileConnected()

        val stillOpenAfterWait = withTimeoutOrNull(watchFor) { ended.await() } == null
        collector.cancel()
        if (stillOpenAfterWait) SseEndState.STILL_OPEN else SseEndState.CLOSED
    }
