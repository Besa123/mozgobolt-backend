/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.feature.vehicleTracking.domain.NoOpVehicleTrackingMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MicrometerVehicleTrackingMetricsTest {
    @Test
    fun `telemetry counters timers gauges and writer age reflect local work`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerVehicleTrackingMetrics(registry)
        val writerAge =
            registry
                .get("mozgobolt.vehicle_tracking.telemetry.history.writer.last_flush_age_seconds")
                .gauge()
        assertEquals(-1.0, writerAge.value())

        metrics.recordHistoryBufferOverflow(droppedPoints = 3)
        metrics.recordHistoryBatchRetry()
        metrics.recordHistoryBatchFlush(persistedPoints = 5, elapsedNanos = 250_000_000)
        metrics.liveMapSubscriberOpened()
        metrics.liveMapSubscriberOpened()
        metrics.liveMapSubscriberClosed()

        assertEquals(
            3.0,
            registry.get("mozgobolt.vehicle_tracking.telemetry.history.buffer.overflow.points").counter().count(),
        )
        assertEquals(
            1.0,
            registry.get("mozgobolt.vehicle_tracking.telemetry.history.batch.retries").counter().count(),
        )
        assertEquals(
            5.0,
            registry.get("mozgobolt.vehicle_tracking.telemetry.history.points.persisted").counter().count(),
        )
        assertEquals(
            1,
            registry.get("mozgobolt.vehicle_tracking.telemetry.history.batch.flush").timer().count(),
        )
        metrics.recordHistoryBatchFlush(persistedPoints = 0, elapsedNanos = 0)
        assertEquals(
            2,
            registry.get("mozgobolt.vehicle_tracking.telemetry.history.batch.flush").timer().count(),
        )
        assertEquals(
            1.0,
            registry.get("mozgobolt.vehicle_tracking.live_map.subscribers.active").gauge().value(),
        )
        assertTrue(writerAge.value() >= 0.0)
    }

    @Test
    fun `invalid operational metric inputs fail fast`() {
        val metrics = MicrometerVehicleTrackingMetrics(SimpleMeterRegistry())

        assertFailsWith<IllegalArgumentException> { metrics.recordHistoryBufferOverflow(droppedPoints = 0) }
        assertFailsWith<IllegalArgumentException> {
            metrics.recordHistoryBatchFlush(persistedPoints = -1, elapsedNanos = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            metrics.recordHistoryBatchFlush(persistedPoints = 0, elapsedNanos = -1)
        }
        assertFailsWith<IllegalStateException> { metrics.liveMapSubscriberClosed() }
    }

    @Test
    fun `live subscriber gauge stays consistent under concurrent churn`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerVehicleTrackingMetrics(registry)
        val workers = Executors.newFixedThreadPool(WORKER_COUNT)
        val subscribers = registry.get("mozgobolt.vehicle_tracking.live_map.subscribers.active").gauge()

        try {
            repeatConcurrently(workers) { metrics.liveMapSubscriberOpened() }
            assertEquals((WORKER_COUNT * OPERATIONS_PER_WORKER).toDouble(), subscribers.value())

            repeatConcurrently(workers) { metrics.liveMapSubscriberClosed() }
            assertEquals(0.0, subscribers.value())
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `disabled operational metrics accept events without collecting state`() {
        NoOpVehicleTrackingMetrics.recordHistoryBufferOverflow(droppedPoints = 1)
        NoOpVehicleTrackingMetrics.recordHistoryBatchRetry()
        NoOpVehicleTrackingMetrics.recordHistoryBatchFlush(persistedPoints = 1, elapsedNanos = 1)
        NoOpVehicleTrackingMetrics.liveMapSubscriberOpened()
        NoOpVehicleTrackingMetrics.liveMapSubscriberClosed()
    }

    private fun repeatConcurrently(
        workers: ExecutorService,
        operation: () -> Unit,
    ) {
        val futures =
            (0 until WORKER_COUNT).map {
                workers.submit {
                    repeat(OPERATIONS_PER_WORKER) { operation() }
                }
            }
        futures.forEach { it.get(5, TimeUnit.SECONDS) }
    }

    private companion object {
        const val WORKER_COUNT = 8
        const val OPERATIONS_PER_WORKER = 1_000
    }
}
