/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.domain

/** Operational measurements for the live-map and telemetry-history paths. */
interface VehicleTrackingMetrics {
    /** Records points omitted from durable history because the in-memory buffer was full. */
    fun recordHistoryBufferOverflow(droppedPoints: Int)

    /** Records a failed writer iteration that will be retried on its next interval. */
    fun recordHistoryBatchRetry()

    /** Records a completed writer iteration and the number of points it persisted. */
    fun recordHistoryBatchFlush(
        persistedPoints: Int,
        elapsedNanos: Long,
    )

    /** Records a live-map subscription opening on this instance. */
    fun liveMapSubscriberOpened()

    /** Records a live-map subscription closing on this instance. */
    fun liveMapSubscriberClosed()
}

/** No-op implementation for disabled metrics and isolated unit tests. */
object NoOpVehicleTrackingMetrics : VehicleTrackingMetrics {
    override fun recordHistoryBufferOverflow(droppedPoints: Int) = Unit

    override fun recordHistoryBatchRetry() = Unit

    override fun recordHistoryBatchFlush(
        persistedPoints: Int,
        elapsedNanos: Long,
    ) = Unit

    override fun liveMapSubscriberOpened() = Unit

    override fun liveMapSubscriberClosed() = Unit
}
