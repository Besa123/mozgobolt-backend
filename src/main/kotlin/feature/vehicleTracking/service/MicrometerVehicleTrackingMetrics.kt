/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.feature.vehicleTracking.domain.VehicleTrackingMetrics
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.ToDoubleFunction

class MicrometerVehicleTrackingMetrics(
    registry: MeterRegistry,
) : VehicleTrackingMetrics {
    private val droppedHistoryPoints =
        Counter
            .builder("mozgobolt.vehicle_tracking.telemetry.history.buffer.overflow.points")
            .description("Telemetry history points dropped because the bounded in-memory buffer was full")
            .register(registry)
    private val historyBatchRetries =
        Counter
            .builder("mozgobolt.vehicle_tracking.telemetry.history.batch.retries")
            .description("Failed telemetry history flushes that will be retried on the next writer interval")
            .register(registry)
    private val persistedHistoryPoints =
        Counter
            .builder("mozgobolt.vehicle_tracking.telemetry.history.points.persisted")
            .description("Telemetry history points persisted to PostgreSQL")
            .register(registry)
    private val historyBatchFlush =
        Timer
            .builder("mozgobolt.vehicle_tracking.telemetry.history.batch.flush")
            .description("Duration of a telemetry history writer iteration")
            .register(registry)
    private val activeLiveMapSubscribers = AtomicInteger()
    private val lastWriterFlushAtMillis = AtomicLong()

    init {
        val activeSubscribersGauge =
            Gauge
                .builder(
                    "mozgobolt.vehicle_tracking.live_map.subscribers.active",
                    activeLiveMapSubscribers,
                    ToDoubleFunction { subscribers -> subscribers.get().toDouble() },
                )
        activeSubscribersGauge.description("Active in-memory live-map subscriptions on this instance")
        activeSubscribersGauge.register(registry)

        val writerFlushAgeGauge =
            Gauge
                .builder(
                    "mozgobolt.vehicle_tracking.telemetry.history.writer.last_flush_age_seconds",
                    lastWriterFlushAtMillis,
                    ToDoubleFunction { lastWriterFlush ->
                        val lastFlushAt = lastWriterFlush.get()
                        if (lastFlushAt == 0L) {
                            -1.0
                        } else {
                            (System.currentTimeMillis() - lastFlushAt).coerceAtLeast(0L) / MILLIS_PER_SECOND
                        }
                    },
                )
        writerFlushAgeGauge.description(
            "Age of the last completed telemetry history writer iteration; -1 means none has completed",
        )
        writerFlushAgeGauge.register(registry)
    }

    override fun recordHistoryBufferOverflow(droppedPoints: Int) {
        require(droppedPoints > 0) { "droppedPoints must be positive" }
        droppedHistoryPoints.increment(droppedPoints.toDouble())
    }

    override fun recordHistoryBatchRetry() {
        historyBatchRetries.increment()
    }

    override fun recordHistoryBatchFlush(
        persistedPoints: Int,
        elapsedNanos: Long,
    ) {
        require(persistedPoints >= 0) { "persistedPoints must not be negative" }
        require(elapsedNanos >= 0) { "elapsedNanos must not be negative" }
        if (persistedPoints > 0) persistedHistoryPoints.increment(persistedPoints.toDouble())
        historyBatchFlush.record(elapsedNanos, TimeUnit.NANOSECONDS)
        lastWriterFlushAtMillis.set(System.currentTimeMillis())
    }

    override fun liveMapSubscriberOpened() {
        activeLiveMapSubscribers.incrementAndGet()
    }

    override fun liveMapSubscriberClosed() {
        val remaining = activeLiveMapSubscribers.decrementAndGet()
        check(remaining >= 0) { "Live-map subscriber count cannot be negative" }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
    }
}
