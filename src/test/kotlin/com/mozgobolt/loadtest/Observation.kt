/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryPoint
import kotlin.time.Duration
import kotlin.time.TimeSource

/** A reading of the monotonic clock; differences between two marks are real elapsed time. */
internal typealias MonotonicMark = TimeSource.Monotonic.ValueTimeMark

/**
 * Everything the actors report. Actors never touch shared counters: they emit these facts into one
 * channel and a single collector folds them into the [RunLedger], so there is no locking and no
 * shared mutable state between the hundreds of concurrent coroutines.
 */
internal sealed interface Observation {
    data class RequestCompleted(
        val name: String,
        val status: Int,
        val latency: Duration,
    ) : Observation

    /** Emitted before the request leaves, so any live event it causes can be matched to its send time. */
    data class TelemetrySent(
        val vehicleId: Int,
        val points: List<TelemetryPoint>,
        val sentAt: MonotonicMark,
    ) : Observation

    data class TelemetryAcknowledged(
        val vehicleId: Int,
        val status: Int,
    ) : Observation

    data class ViewerConnecting(
        val sessionId: Int,
        val center: GeoPoint,
        val radiusKm: Double,
        val at: MonotonicMark,
        val nodeName: String = "node-a",
    ) : Observation

    data class ViewerEventReceived(
        val sessionId: Int,
        val event: LiveFeedEvent,
        val at: MonotonicMark,
        val nodeName: String = "node-a",
    ) : Observation

    /** The client ended the session on purpose (the viewer closed the map or the run finished). */
    data class ViewerLeft(
        val sessionId: Int,
    ) : Observation

    /** A viewer's map compared with what the fleet's accepted positions say it should show. */
    data class MapChecked(
        val sessionId: Int,
        val phase: CheckPhase,
        val map: LiveMapState,
    ) : Observation

    /** How late the generator itself ran; large values mean the load generator, not the server, is the bottleneck. */
    data class GeneratorLag(
        val source: LagSource,
        val lag: Duration,
    ) : Observation

    data class FunctionalFailure(
        val message: String,
    ) : Observation
}

internal enum class CheckPhase { SETTLED, FINAL }

internal enum class LagSource { SCHEDULER, TRUCK_TICK }

/** Where actors report; implementations must be safe to call from any coroutine. */
internal fun interface ObservationSink {
    fun record(observation: Observation)
}
