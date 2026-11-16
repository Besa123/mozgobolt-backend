/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryPoint
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class RunLedgerTest {
    private val center = GeoPoint(47.4979, 19.0402)
    private val nearby = center.movedBy(1.0, 0.0)
    private val faraway = center.movedBy(9.0, 0.0)
    private val start = TimeSource.Monotonic.markNow()
    private val description =
        RunDescription("test", 30, 1, 1, 5.0, 5.0, 1, 0, loginBurst = false, seed = 1)

    private val ledger = RunLedger()

    private fun markAt(offsetMillis: Long) = start + offsetMillis.milliseconds

    private fun connectViewer(sessionId: Int = 1) {
        ledger.record(Observation.ViewerConnecting(sessionId, center, 5.0, markAt(0)))
        ledger.record(Observation.ViewerEventReceived(sessionId, LiveFeedEvent.Connected(2.milliseconds), markAt(0)))
    }

    private fun point(
        position: GeoPoint,
        second: Long,
    ) = TelemetryPoint(position.latitude, position.longitude, Instant.EPOCH.plusSeconds(second))

    private fun accept(
        vehicleId: Int,
        position: GeoPoint,
        second: Long,
        sentAtMillis: Long,
        status: Int = 202,
    ): TelemetryPoint =
        point(position, second).also {
            ledger.record(Observation.TelemetrySent(vehicleId, listOf(it), markAt(sentAtMillis)))
            ledger.record(Observation.TelemetryAcknowledged(vehicleId, status))
        }

    private fun received(
        vehicleId: Int,
        position: GeoPoint,
        second: Long,
        atMillis: Long,
        sessionId: Int = 1,
    ) {
        val fix = VehicleFix(vehicleId, position, Instant.EPOCH.plusSeconds(second))
        ledger.record(Observation.ViewerEventReceived(sessionId, LiveFeedEvent.Location(fix), markAt(atMillis)))
    }

    private fun report() = ledger.report(description)

    @Test
    fun `a live delivery is matched to its send time and counted against what was expected`() {
        connectViewer()
        accept(vehicleId = 7, position = nearby, second = 1, sentAtMillis = 1_000)
        received(7, nearby, second = 1, atMillis = 1_025)

        val live = report().liveFeed

        assertEquals(1L, live.expectedLiveDeliveries)
        assertEquals(1L, live.observedLiveDeliveries)
        assertEquals(1.0, live.liveDeliveryRatio)
        assertEquals(25.0, live.deliveryLatency.p50Ms, absoluteTolerance = 25 * 0.025)
        assertTrue(report().passed)
    }

    @Test
    fun `an in-radius point that never arrives lowers the delivery ratio and warns`() {
        connectViewer()
        accept(vehicleId = 7, position = nearby, second = 1, sentAtMillis = 1_000)

        val report = report()

        assertEquals(0.0, report.liveFeed.liveDeliveryRatio)
        assertTrue(report.warnings.any { "coalesced" in it })
    }

    @Test
    fun `points sent before the subscription had settled are not expected deliveries`() {
        connectViewer()
        accept(vehicleId = 7, position = nearby, second = 1, sentAtMillis = 100)
        accept(vehicleId = 8, position = faraway, second = 1, sentAtMillis = 2_000)

        assertEquals(
            0L,
            report().liveFeed.expectedLiveDeliveries,
            "the first is too early, the second is out of radius",
        )
    }

    @Test
    fun `an event outside the requested radius is a functional failure`() {
        connectViewer()
        accept(vehicleId = 7, position = faraway, second = 1, sentAtMillis = 1_000)
        received(7, faraway, second = 1, atMillis = 1_020)

        val report = report()

        assertEquals(1, report.liveFeed.outsideRadiusEvents)
        assertTrue("live event outside the requested radius" in report.functionalFailures)
    }

    @Test
    fun `a final map check flags a missing in-radius vehicle and a shown out-of-radius vehicle`() {
        connectViewer()
        accept(vehicleId = 1, position = nearby, second = 1, sentAtMillis = 1_000)
        accept(vehicleId = 2, position = faraway, second = 1, sentAtMillis = 1_000)
        val phantomMap =
            LiveMapState(mapOf(2 to VehicleFix(2, faraway, Instant.EPOCH)))

        ledger.record(Observation.MapChecked(1, CheckPhase.FINAL, phantomMap))

        assertEquals(
            setOf(
                "FINAL map check: an in-radius vehicle is missing",
                "FINAL map check: an out-of-radius vehicle is shown",
            ),
            report().functionalFailures.keys,
        )
    }

    @Test
    fun `a settled map check tolerates a vehicle sitting on the radius edge`() {
        connectViewer()
        val onTheEdge = center.movedBy(4.95, 90.0)
        accept(vehicleId = 1, position = onTheEdge, second = 1, sentAtMillis = 1_000)

        ledger.record(Observation.MapChecked(1, CheckPhase.SETTLED, LiveMapState.EMPTY))

        assertTrue(report().passed, "a vehicle within the margin of the edge may have just crossed it")
    }

    @Test
    fun `a map that matches the fleet passes both checks`() {
        connectViewer()
        accept(vehicleId = 1, position = nearby, second = 1, sentAtMillis = 1_000)
        val map = LiveMapState(mapOf(1 to VehicleFix(1, nearby, Instant.EPOCH)))

        ledger.record(Observation.MapChecked(1, CheckPhase.SETTLED, map))
        ledger.record(Observation.MapChecked(1, CheckPhase.FINAL, map))

        assertTrue(report().passed)
    }

    @Test
    fun `rejections, dropped streams, and refused telemetry are reported as failures`() {
        connectViewer(sessionId = 1)
        ledger.record(Observation.ViewerConnecting(2, center, 5.0, markAt(0)))
        ledger.record(Observation.ViewerEventReceived(2, LiveFeedEvent.Rejected(429), markAt(1)))
        ledger.record(Observation.ViewerEventReceived(1, LiveFeedEvent.Closed(cause = null), markAt(2)))
        accept(vehicleId = 1, position = nearby, second = 1, sentAtMillis = 1_000, status = 500)

        val report = report()

        assertEquals(
            setOf(
                "live connection rejected (non-200)",
                "live stream ended without the client closing it",
                "telemetry batch was not accepted with HTTP 202",
            ),
            report.functionalFailures.keys,
        )
        assertEquals(1, report.httpStatuses["sse.connect:429"])
    }

    @Test
    fun `a saturated load generator is called out because it would inflate every latency`() {
        ledger.record(Observation.GeneratorLag(LagSource.SCHEDULER, 2.seconds))

        assertTrue(report().warnings.any { "generator itself is saturated" in it })
    }
}
