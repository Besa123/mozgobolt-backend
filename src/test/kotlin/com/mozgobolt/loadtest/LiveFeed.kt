/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import java.time.Instant
import kotlin.time.Duration

/** One vehicle position as a buyer's live map receives it. */
internal data class VehicleFix(
    val vehicleId: Int,
    val position: GeoPoint,
    val recordedAt: Instant,
)

/** Everything a client can observe on one `GET /vehicle-tracking/live` connection, in wire order. */
internal sealed interface LiveFeedEvent {
    /** The SSE handshake completed; [latency] is the time to response headers. */
    data class Connected(
        val latency: Duration,
    ) : LiveFeedEvent

    data class Location(
        val fix: VehicleFix,
    ) : LiveFeedEvent

    data class Offline(
        val vehicleId: Int,
    ) : LiveFeedEvent

    /** The server asked the client to reconnect with a fresh token. */
    data object Reconnect : LiveFeedEvent

    data class ServerError(
        val payload: String?,
    ) : LiveFeedEvent

    /** The server answered the connection attempt with a non-200 [status] (for example a rate limit). */
    data class Rejected(
        val status: Int,
    ) : LiveFeedEvent

    /** A frame the client could not decode, which would be a wire-contract regression. */
    data class Malformed(
        val eventName: String?,
        val reason: String,
    ) : LiveFeedEvent

    /** The stream ended without the client asking for it; [cause] is `null` for a clean server-side close. */
    data class Closed(
        val cause: Throwable?,
    ) : LiveFeedEvent
}

/**
 * What a buyer's map currently shows: the fold of a connection's events, mirroring how a real client
 * applies them (a location upserts the pin, an offline event removes it).
 */
internal data class LiveMapState(
    val vehicles: Map<Int, VehicleFix> = emptyMap(),
) {
    fun apply(event: LiveFeedEvent): LiveMapState =
        when (event) {
            is LiveFeedEvent.Location -> copy(vehicles = vehicles + (event.fix.vehicleId to event.fix))

            is LiveFeedEvent.Offline -> copy(vehicles = vehicles - event.vehicleId)

            is LiveFeedEvent.Connected,
            LiveFeedEvent.Reconnect,
            is LiveFeedEvent.ServerError,
            is LiveFeedEvent.Rejected,
            is LiveFeedEvent.Malformed,
            is LiveFeedEvent.Closed,
            -> this
        }

    companion object {
        val EMPTY = LiveMapState()
    }
}
