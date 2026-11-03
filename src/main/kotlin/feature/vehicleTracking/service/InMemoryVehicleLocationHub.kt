/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.feature.vehicleTracking.domain.CellIndexer
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.model.CellId
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLiveEvent
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val BROADCAST_BUFFER_CAPACITY = 64
private const val LIVE_LOCATION_STALE_AFTER_MILLIS = 5 * 60 * 1000L

// The global request safety net is three minutes; keep an ended-session fence longer than any
// in-flight telemetry request or ordinary relay attempt can remain outstanding.
internal const val ASSIGNMENT_FENCE_RETENTION_SECONDS = 300L
private const val ASSIGNMENT_FENCE_SWEEP_INTERVAL_MILLIS = 60_000L

/**
 * [VehicleLocationHub] partitioned by [CellIndexer] cell, mirroring the per-key `SharedFlow` shape
 * `InMemorySyncEventHub` already uses for per-user partitioning — here the key is geography
 * instead of a user id.
 *
 * `flowsByCell` grows with the number of *distinct* cells anyone has ever published to or
 * subscribed near — in practice small and slow-growing for a regional service, since real queries
 * cluster around where vehicles and buyers actually are. Not a hard-capped structure — nothing
 * currently restricts a query to a fixed service area, and cross-border use (e.g. a buyer near the
 * Slovakian border) is deliberately still allowed, not rejected — but a cell with zero current
 * subscribers is reclaimed by a [com.mozgobolt.core.utility.IdleSharedFlowSweeper] wired over this
 * map (see the vehicleTracking DI module), so distinct-cells-ever-seen no longer means
 * distinct-cells-held-in-memory-forever. `assignmentFences` retains only a small ended-session
 * marker for five minutes so late telemetry cannot resurrect an ended shift; the same sweeper
 * reclaims those markers. `internal`, not `private`, only so that sweeper can be constructed with
 * a reference to the cell-flow map.
 */
class InMemoryVehicleLocationHub(
    private val cellIndexer: CellIndexer,
) : VehicleLocationHub {
    private data class AssignmentFence(
        val assignmentId: Int,
        val isEnded: Boolean,
        val endedAt: Instant? = null,
    )

    private data class PublishedLocation(
        val update: VehicleLocationUpdate,
        val receivedAtMillis: Long,
    )

    private val latestByVehicleId = ConcurrentHashMap<Int, PublishedLocation>()
    private val publiclyVisibleVehicleIds = ConcurrentHashMap.newKeySet<Int>()

    // Keep a short-lived high-water mark after a shift ends so in-flight telemetry and relay messages
    // cannot resurrect it. Vehicle assignment IDs increase across sessions on the same locked vehicle.
    private val assignmentFences = ConcurrentHashMap<Int, AssignmentFence>()
    private val lastFenceSweepAtMillis = AtomicLong(0)
    internal val flowsByCell = ConcurrentHashMap<CellId, MutableSharedFlow<VehicleLiveEvent>>()

    override fun publish(update: VehicleLocationUpdate) {
        publishIfCurrent(update)
    }

    override fun publishIfCurrent(update: VehicleLocationUpdate): Boolean {
        sweepExpiredAssignmentFences()
        var wasAccepted = false
        assignmentFences.compute(update.vehicleId) { _, fence ->
            val assignmentId = update.assignmentId
            when {
                fence != null && (assignmentId == null || assignmentId < fence.assignmentId) -> {
                    fence
                }

                fence != null && assignmentId == fence.assignmentId && fence.isEnded -> {
                    fence
                }

                else -> {
                    val previous = latestByVehicleId[update.vehicleId]?.update
                    latestByVehicleId[update.vehicleId] =
                        PublishedLocation(update, System.currentTimeMillis())
                    if (update.sharesLocationWithCustomers) {
                        publiclyVisibleVehicleIds.add(update.vehicleId)
                        emitPosition(update)
                    } else if (publiclyVisibleVehicleIds.remove(update.vehicleId)) {
                        previous?.let(::emitOffline)
                    }
                    wasAccepted = true
                    assignmentId?.let { AssignmentFence(it, isEnded = false) } ?: fence
                }
            }
        }
        return wasAccepted
    }

    override fun markOffline(vehicleId: Int) {
        sweepExpiredAssignmentFences()
        val endedAt = Instant.now()
        assignmentFences.compute(vehicleId) { _, fence ->
            if (fence == null) {
                val removedLocation = latestByVehicleId.remove(vehicleId)
                if (removedLocation != null && publiclyVisibleVehicleIds.remove(vehicleId)) {
                    emitOffline(removedLocation.update)
                }
                null
            } else if (fence.isEnded) {
                fence
            } else {
                val latest = latestByVehicleId[vehicleId]?.update
                val isLatestForAssignment =
                    latest?.assignmentId == null || latest.assignmentId == fence.assignmentId
                if (latest != null && isLatestForAssignment) {
                    val current = latestByVehicleId[vehicleId]
                    val wasRemoved = current?.update == latest && latestByVehicleId.remove(vehicleId, current)
                    if (wasRemoved && publiclyVisibleVehicleIds.remove(vehicleId)) emitOffline(latest)
                }
                fence.copy(isEnded = true, endedAt = endedAt)
            }
        }
    }

    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod") // Branches model assignment-fence states atomically.
    override fun markOffline(
        vehicleId: Int,
        assignmentId: Int,
    ): Boolean {
        sweepExpiredAssignmentFences()
        val endedAt = Instant.now()
        var wasAccepted = false
        assignmentFences.compute(vehicleId) { _, fence ->
            when {
                fence != null && assignmentId < fence.assignmentId -> {
                    fence
                }

                fence != null && assignmentId == fence.assignmentId && fence.isEnded -> {
                    wasAccepted = true
                    fence
                }

                else -> {
                    val latest = latestByVehicleId[vehicleId]?.update
                    if (latest?.assignmentId != null && latest.assignmentId > assignmentId) {
                        AssignmentFence(latest.assignmentId, isEnded = false)
                    } else {
                        val isLatestForAssignment =
                            latest?.assignmentId == null || latest.assignmentId == assignmentId
                        if (latest != null && isLatestForAssignment) {
                            val published = latestByVehicleId[vehicleId]
                            val wasRemoved =
                                published?.update == latest && latestByVehicleId.remove(vehicleId, published)
                            if (wasRemoved && publiclyVisibleVehicleIds.remove(vehicleId)) emitOffline(latest)
                        }
                        wasAccepted = true
                        AssignmentFence(assignmentId, isEnded = true, endedAt = endedAt)
                    }
                }
            }
        }
        return wasAccepted
    }

    internal fun sweepExpiredAssignmentFences() {
        val nowMillis = System.currentTimeMillis()
        val previousSweep = lastFenceSweepAtMillis.get()
        if (nowMillis - previousSweep < ASSIGNMENT_FENCE_SWEEP_INTERVAL_MILLIS) return
        if (!lastFenceSweepAtMillis.compareAndSet(previousSweep, nowMillis)) return
        pruneExpiredAssignmentFences(Instant.ofEpochMilli(nowMillis))
    }

    internal fun pruneExpiredAssignmentFences(now: Instant) {
        val cutoff = now.minusSeconds(ASSIGNMENT_FENCE_RETENTION_SECONDS)
        assignmentFences.entries.removeIf { (_, fence) ->
            fence.endedAt?.isBefore(cutoff) == true
        }
    }

    internal fun sweepStaleLocations(nowMillis: Long = System.currentTimeMillis()) {
        latestByVehicleId.entries.toList().forEach { (vehicleId, observed) ->
            if (nowMillis - observed.receivedAtMillis < LIVE_LOCATION_STALE_AFTER_MILLIS) return@forEach

            assignmentFences.compute(vehicleId) { _, fence ->
                val current = latestByVehicleId[vehicleId]
                val isCurrentAndStale =
                    current === observed && nowMillis - observed.receivedAtMillis >= LIVE_LOCATION_STALE_AFTER_MILLIS
                val wasRemoved = isCurrentAndStale && latestByVehicleId.remove(vehicleId, observed)
                if (wasRemoved && publiclyVisibleVehicleIds.remove(vehicleId)) emitOffline(observed.update)
                fence
            }
        }
    }

    override fun lastKnownLocation(vehicleId: Int): VehicleLocationUpdate? {
        sweepStaleLocations()
        return latestByVehicleId[vehicleId]?.update
    }

    @Suppress("SpreadOperator") // merge(vararg) is the stable API; the array copy of a few
    // hundred Flow references at most is negligible. (flattenMerge avoids the spread but is
    // @ExperimentalCoroutinesApi and silently caps concurrency at 16 unless you remember to pass
    // it explicitly every time — a worse trap than this lint.)
    override fun subscribeNear(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): Flow<VehicleLiveEvent> {
        val cells = cellIndexer.cellsWithin(latitude, longitude, radiusKm)
        if (cells.isEmpty()) return emptyFlow()

        val cellFlows = cells.map { cell -> flowsByCell.computeIfAbsent(cell) { newBroadcastFlow() } }
        return merge(*cellFlows.toTypedArray())
    }

    override fun snapshotNear(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): List<VehicleLocationUpdate> {
        sweepStaleLocations()
        val cells = cellIndexer.cellsWithin(latitude, longitude, radiusKm)
        return latestByVehicleId.values
            .asSequence()
            .filter { it.update.sharesLocationWithCustomers && it.update.cellId in cells }
            .map(PublishedLocation::update)
            .toList()
    }

    private fun emitPosition(update: VehicleLocationUpdate) {
        val event = VehicleLiveEvent.Position(update)
        flowsByCell[update.cellId]?.tryEmit(event)
    }

    private fun emitOffline(update: VehicleLocationUpdate) {
        val event = VehicleLiveEvent.Offline(update.vehicleId)
        flowsByCell[update.cellId]?.tryEmit(event)
    }

    private fun newBroadcastFlow() =
        MutableSharedFlow<VehicleLiveEvent>(
            replay = 0,
            extraBufferCapacity = BROADCAST_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
}
