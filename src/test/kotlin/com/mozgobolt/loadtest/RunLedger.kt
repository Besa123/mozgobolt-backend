/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

// A subscription is registered just after the SSE handshake completes, so points sent in this
// window may legitimately predate it and are not counted as expected deliveries.
private val SUBSCRIPTION_SETTLE = 250.milliseconds

// A map is checked a few seconds after connecting while trucks keep moving: the check tolerates a
// vehicle sitting this close to the radius edge, since it may have crossed it since the snapshot.
private const val SETTLED_MARGIN_KM = 0.2

private const val SCHEDULER_LAG_WARNING_MS = 50.0
private const val DELIVERY_RATIO_WARNING = 0.99
private const val NO_MARGIN_KM = 0.0

private data class PointKey(
    val vehicleId: Int,
    val recordedAt: Instant,
)

private data class SessionLog(
    val center: GeoPoint,
    val radiusKm: Double,
    val liveFrom: MonotonicMark? = null,
)

private val LiveFeedEvent.failureReason: String?
    get() =
        when (this) {
            is LiveFeedEvent.Rejected -> "live connection rejected (non-200)"

            is LiveFeedEvent.ServerError -> "server sent an error event on the live stream"

            is LiveFeedEvent.Malformed -> "live stream frame could not be decoded: $reason"

            is LiveFeedEvent.Closed -> "live stream ended without the client closing it"

            is LiveFeedEvent.Connected,
            is LiveFeedEvent.Location,
            is LiveFeedEvent.Offline,
            LiveFeedEvent.Reconnect,
            -> null
        }

private val LiveFeedEvent.name: String
    get() =
        when (this) {
            is LiveFeedEvent.Connected -> "connected"
            is LiveFeedEvent.Location -> "location"
            is LiveFeedEvent.Offline -> "offline"
            LiveFeedEvent.Reconnect -> "reconnect"
            is LiveFeedEvent.ServerError -> "error"
            is LiveFeedEvent.Rejected -> "rejected"
            is LiveFeedEvent.Malformed -> "malformed"
            is LiveFeedEvent.Closed -> "closed"
        }

/**
 * Folds [Observation]s into the numbers and verdicts of one run. It is deliberately mutable and
 * deliberately single-threaded: exactly one collector coroutine calls [record], so it needs no
 * synchronisation, and [report] is called only after that collector has finished.
 */
internal class RunLedger {
    private val requestLatency = mutableMapOf<String, LatencyHistogram>()
    private val statusCounts = sortedMapOf<String, Int>()
    private val eventCounts = sortedMapOf<String, Int>()
    private val generatorLag = mutableMapOf<LagSource, LatencyHistogram>()
    private val failures = linkedMapOf<String, Int>()
    private val deliveryLatency = LatencyHistogram()

    private val sentAt = HashMap<PointKey, MonotonicMark>()
    private val inFlightBatches = HashMap<Int, List<GeoPoint>>()
    private val lastAcceptedByVehicle = HashMap<Int, GeoPoint>()
    private val sessions = HashMap<Int, SessionLog>()

    private var sessionsOpened = 0
    private var expectedDeliveries = 0L
    private var observedDeliveries = 0L
    private var outsideRadiusEvents = 0

    fun record(observation: Observation) {
        when (observation) {
            is Observation.RequestCompleted -> {
                recordRequest(observation)
            }

            is Observation.TelemetrySent -> {
                recordTelemetrySent(observation)
            }

            is Observation.TelemetryAcknowledged -> {
                recordTelemetryAcknowledged(observation)
            }

            is Observation.ViewerConnecting -> {
                recordViewerConnecting(observation)
            }

            is Observation.ViewerEventReceived -> {
                recordViewerEvent(observation)
            }

            is Observation.ViewerLeft -> {
                sessions.remove(observation.sessionId)
            }

            is Observation.MapChecked -> {
                recordMapCheck(observation)
            }

            is Observation.GeneratorLag -> {
                generatorLag.getOrPut(observation.source, ::LatencyHistogram).record(observation.lag)
            }

            is Observation.FunctionalFailure -> {
                fail(observation.message)
            }
        }
    }

    fun report(description: RunDescription): LoadTestReport {
        val deliveryRatio = if (expectedDeliveries == 0L) 1.0 else observedDeliveries.toDouble() / expectedDeliveries
        return LoadTestReport(
            run = description,
            requests = requestLatency.mapValues { it.value.summary() }.toSortedMap(),
            httpStatuses = statusCounts,
            liveFeed =
                LiveFeedSummary(
                    events = eventCounts,
                    sessionsOpened = sessionsOpened,
                    outsideRadiusEvents = outsideRadiusEvents,
                    expectedLiveDeliveries = expectedDeliveries,
                    observedLiveDeliveries = observedDeliveries,
                    liveDeliveryRatio = deliveryRatio,
                    deliveryLatency = deliveryLatency.summary(),
                ),
            generator = generatorLag.mapKeys { it.key.name }.mapValues { it.value.summary() }.toSortedMap(),
            warnings = warnings(deliveryRatio),
            functionalFailures = failures,
        )
    }

    private fun recordRequest(observation: Observation.RequestCompleted) {
        requestLatency.getOrPut(observation.name, ::LatencyHistogram).record(observation.latency)
        statusCounts.merge("${observation.name}:${observation.status}", 1, Int::plus)
    }

    private fun recordTelemetrySent(observation: Observation.TelemetrySent) {
        observation.points.forEach { sentAt[PointKey(observation.vehicleId, it.recordedAt)] = observation.sentAt }
        inFlightBatches[observation.vehicleId] = observation.points.map { GeoPoint(it.latitude, it.longitude) }
        sessions.values
            .filter { it.isLiveAt(observation.sentAt) }
            .forEach { session ->
                expectedDeliveries +=
                    observation.points.count { point ->
                        session.center.distanceKmTo(GeoPoint(point.latitude, point.longitude)) <= session.radiusKm
                    }
            }
    }

    private fun recordTelemetryAcknowledged(observation: Observation.TelemetryAcknowledged) {
        val batch = inFlightBatches.remove(observation.vehicleId)
        if (observation.status == HTTP_ACCEPTED && batch != null) {
            lastAcceptedByVehicle[observation.vehicleId] = batch.last()
        } else {
            fail("telemetry batch was not accepted with HTTP 202")
        }
    }

    private fun recordViewerConnecting(observation: Observation.ViewerConnecting) {
        sessionsOpened++
        sessions[observation.sessionId] = SessionLog(observation.center, observation.radiusKm)
    }

    private fun recordViewerEvent(observation: Observation.ViewerEventReceived) {
        val session = sessions[observation.sessionId] ?: return
        val event = observation.event
        eventCounts.merge(event.name, 1, Int::plus)
        event.failureReason?.let(::fail)

        if (event is LiveFeedEvent.Connected) {
            sessions[observation.sessionId] = session.copy(liveFrom = observation.at + SUBSCRIPTION_SETTLE)
            requestLatency.getOrPut(SSE_CONNECT, ::LatencyHistogram).record(event.latency)
            statusCounts.merge("$SSE_CONNECT:$HTTP_OK", 1, Int::plus)
        }
        if (event is LiveFeedEvent.Rejected) statusCounts.merge("$SSE_CONNECT:${event.status}", 1, Int::plus)
        if (event is LiveFeedEvent.Location) recordLocation(session, event.fix, observation.at)
    }

    private fun recordLocation(
        session: SessionLog,
        fix: VehicleFix,
        receivedAt: MonotonicMark,
    ) {
        if (session.center.distanceKmTo(fix.position) > session.radiusKm) {
            outsideRadiusEvents++
            fail("live event outside the requested radius")
        }
        val sentTime = sentAt[PointKey(fix.vehicleId, fix.recordedAt)]
        if (sentTime != null && session.isLiveAt(sentTime)) {
            observedDeliveries++
            deliveryLatency.record(receivedAt - sentTime)
        }
    }

    private fun recordMapCheck(observation: Observation.MapChecked) {
        val session = sessions[observation.sessionId] ?: return
        val margin = if (observation.phase == CheckPhase.SETTLED) SETTLED_MARGIN_KM else NO_MARGIN_KM

        fun distanceKm(vehicleId: Int) = lastAcceptedByVehicle.getValue(vehicleId).let(session.center::distanceKmTo)

        val mustShow = lastAcceptedByVehicle.keys.filter { distanceKm(it) <= session.radiusKm - margin }
        val mayShow = lastAcceptedByVehicle.keys.filter { distanceKm(it) <= session.radiusKm + margin }.toSet()
        val shown = observation.map.vehicles.keys

        if (mustShow.any { it !in shown }) fail("${observation.phase} map check: an in-radius vehicle is missing")
        if (shown.any { it !in mayShow }) fail("${observation.phase} map check: an out-of-radius vehicle is shown")
    }

    private fun SessionLog.isLiveAt(mark: MonotonicMark): Boolean = liveFrom?.let { mark >= it } == true

    private fun fail(message: String) {
        failures.merge(message, 1, Int::plus)
    }

    private fun warnings(deliveryRatio: Double): List<String> =
        buildList {
            val schedulerLagP99 = generatorLag[LagSource.SCHEDULER]?.summary()?.p99Ms ?: 0.0
            if (schedulerLagP99 > SCHEDULER_LAG_WARNING_MS) {
                add(
                    "Load generator scheduler lag p99 is ${schedulerLagP99}ms: the generator itself is saturated, " +
                        "so latencies are inflated. Run it on a separate machine or reduce the load.",
                )
            }
            if (deliveryRatio < DELIVERY_RATIO_WARNING) {
                add(
                    "Only ${"%.2f".format(deliveryRatio * PERCENT)}% of in-radius live positions were delivered; " +
                        "intermediate points are coalesced when a subscriber's bounded buffer overflows.",
                )
            }
        }

    companion object {
        const val SSE_CONNECT = "sse.connect"
        const val HTTP_OK = 200
        const val HTTP_ACCEPTED = 202
        private const val PERCENT = 100
    }
}
