/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.domain

import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate

/** Holds telemetry between ingestion and the periodic slow-path batch write. */
interface VehicleLocationBuffer {
    /**
     * Queues a history copy of [update]. Returns `false` when the bounded history buffer is full;
     * the live-location path is independent of this result.
     */
    fun enqueue(update: VehicleLocationUpdate): Boolean

    /**
     * Claims the current queue for one persistence attempt. Claimed rows continue to count against
     * capacity until [completeBatch] or [restoreFailedBatch] is called.
     */
    fun drainAll(): List<VehicleLocationUpdate>

    /** Releases capacity after the claimed batch has been persisted or invalid rows discarded. */
    fun completeBatch(batch: List<VehicleLocationUpdate>)

    /** Restores a failed claimed batch ahead of newer points, preserving FIFO history order. */
    fun restoreFailedBatch(batch: List<VehicleLocationUpdate>)
}
