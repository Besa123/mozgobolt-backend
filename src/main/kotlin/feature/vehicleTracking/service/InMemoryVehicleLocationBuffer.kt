/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationBuffer
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import java.util.ArrayDeque

private const val DEFAULT_MAX_PENDING_UPDATES = 50_000

class InMemoryVehicleLocationBuffer(
    private val maxPendingUpdates: Int = DEFAULT_MAX_PENDING_UPDATES,
) : VehicleLocationBuffer {
    private val lock = Any()
    private val queue = ArrayDeque<VehicleLocationUpdate>()
    private var pendingCount = 0
    private var inFlightBatch: List<VehicleLocationUpdate>? = null

    init {
        require(maxPendingUpdates > 0) { "maxPendingUpdates must be positive" }
    }

    override fun enqueue(update: VehicleLocationUpdate): Boolean =
        synchronized(lock) {
            if (pendingCount >= maxPendingUpdates) return@synchronized false

            queue.addLast(update)
            pendingCount++
            true
        }

    override fun drainAll(): List<VehicleLocationUpdate> =
        synchronized(lock) {
            if (inFlightBatch != null || queue.isEmpty()) return@synchronized emptyList()

            queue.toList().also { batch ->
                queue.clear()
                inFlightBatch = batch
            }
        }

    override fun completeBatch(batch: List<VehicleLocationUpdate>) {
        synchronized(lock) {
            check(inFlightBatch === batch) { "Only the currently claimed telemetry batch can be completed" }
            pendingCount -= batch.size
            inFlightBatch = null
        }
    }

    override fun restoreFailedBatch(batch: List<VehicleLocationUpdate>) {
        synchronized(lock) {
            check(inFlightBatch === batch) { "Only the currently claimed telemetry batch can be restored" }
            batch.asReversed().forEach(queue::addFirst)
            inFlightBatch = null
        }
    }
}
