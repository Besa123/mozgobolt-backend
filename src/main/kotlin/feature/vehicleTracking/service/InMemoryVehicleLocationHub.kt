/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.feature.vehicleTracking.domain.CellIndexer
import com.mozgobolt.feature.vehicleTracking.domain.NearbyLocationFeed
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.haversineKm
import com.mozgobolt.feature.vehicleTracking.domain.model.CellId
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLiveEvent
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import java.time.Instant
import java.util.UUID

private const val LIVE_LOCATION_STALE_AFTER_MILLIS = 5 * 60 * 1000L

// Keep an ended-session fence longer than any in-flight telemetry request or ordinary relay attempt
// can remain outstanding.
internal const val ASSIGNMENT_FENCE_RETENTION_SECONDS = 300L
private const val ASSIGNMENT_FENCE_SWEEP_INTERVAL_MILLIS = 60_000L

/**
 * Keeps one conflated pending-event set per live client and routes updates through H3-cell subscriber
 * sets. The cell map contains only cells with active subscribers; closing a feed removes its registrations.
 */
@Suppress("TooManyFunctions") // State mutation, nearby-feed routing, and bounded cache cleanup share one lock.
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

    /**
     * What one subscriber has not consumed yet, conflated per vehicle: a newer event for a vehicle
     * replaces that vehicle's older pending one. A slow consumer therefore skips intermediate
     * positions but can never lose the latest state of any vehicle (in particular an [VehicleLiveEvent.Offline]
     * that a fixed FIFO buffer would silently drop, leaving a ghost pin on the map), and memory stays
     * bounded by the number of vehicles the subscriber can see.
     */
    private class PendingLiveEvents {
        private val latestByVehicle = LinkedHashMap<Int, VehicleLiveEvent>()
        private val wakeUp = Channel<Unit>(Channel.CONFLATED)

        fun offer(event: VehicleLiveEvent) {
            val vehicleId = event.vehicleId
            synchronized(latestByVehicle) {
                latestByVehicle.remove(vehicleId)
                latestByVehicle[vehicleId] = event
            }
            wakeUp.trySend(Unit)
        }

        /** Returns `true` only for the call that actually closed it, so closing is idempotent. */
        fun close(): Boolean = wakeUp.close()

        /**
         * Emits pending events as they arrive and completes once [close]d and fully drained: each pass
         * takes everything pending, and only waits for the next offer when nothing is.
         */
        fun asFlow(): Flow<VehicleLiveEvent> =
            flow {
                while (true) {
                    val batch = takePending()
                    if (batch.isNotEmpty()) {
                        batch.forEach { emit(it) }
                    } else if (wakeUp.receiveCatching().isClosed) {
                        return@flow
                    }
                }
            }

        private fun takePending(): List<VehicleLiveEvent> =
            synchronized(latestByVehicle) {
                latestByVehicle.values.toList().also { latestByVehicle.clear() }
            }
    }

    private class NearbySubscriber(
        val latitude: Double,
        val longitude: Double,
        val radiusKm: Double,
        val cells: Set<CellId>,
        initialVehicleIds: Collection<Int>,
    ) {
        val pending = PendingLiveEvents()
        val visibleVehicleIds = initialVehicleIds.toMutableSet()
        val lastForwardedUpdateByVehicle = HashMap<Int, UUID>()
    }

    private val stateLock = Any()
    private val latestByVehicleId = HashMap<Int, PublishedLocation>()
    private val publiclyVisibleVehicleIds = HashSet<Int>()
    private val assignmentFences = HashMap<Int, AssignmentFence>()
    private val subscribersByCell = HashMap<CellId, MutableSet<NearbySubscriber>>()
    private var lastFenceSweepAtMillis = 0L

    override fun publish(update: VehicleLocationUpdate) {
        publishIfCurrent(update)
    }

    override fun publishIfCurrent(update: VehicleLocationUpdate): Boolean =
        synchronized(stateLock) {
            sweepExpiredAssignmentFencesLocked()
            val fence = assignmentFences[update.vehicleId]
            val assignmentId = update.assignmentId
            when {
                fence != null && (assignmentId == null || assignmentId < fence.assignmentId) -> {
                    false
                }

                fence != null && assignmentId == fence.assignmentId && fence.isEnded -> {
                    false
                }

                else -> {
                    val previous = latestByVehicleId[update.vehicleId]?.update
                    latestByVehicleId[update.vehicleId] =
                        PublishedLocation(update, System.currentTimeMillis())
                    if (update.sharesLocationWithCustomers) {
                        publiclyVisibleVehicleIds.add(update.vehicleId)
                        emitPosition(update, previous?.cellId)
                    } else if (publiclyVisibleVehicleIds.remove(update.vehicleId)) {
                        previous?.let(::emitOffline)
                    }
                    assignmentId?.let { assignmentFences[update.vehicleId] = AssignmentFence(it, isEnded = false) }
                    true
                }
            }
        }

    override fun markOffline(vehicleId: Int) {
        synchronized(stateLock) {
            sweepExpiredAssignmentFencesLocked()
            val endedAt = Instant.now()
            val fence = assignmentFences[vehicleId]
            when {
                fence == null -> {
                    val removedLocation = latestByVehicleId.remove(vehicleId)
                    if (removedLocation != null && publiclyVisibleVehicleIds.remove(vehicleId)) {
                        emitOffline(removedLocation.update)
                    }
                }

                fence.isEnded -> {}

                else -> {
                    val latest = latestByVehicleId[vehicleId]?.update
                    val isLatestForAssignment =
                        latest?.assignmentId == null || latest.assignmentId == fence.assignmentId
                    if (latest != null && isLatestForAssignment) {
                        val removedLocation = latestByVehicleId.remove(vehicleId)
                        if (removedLocation != null && publiclyVisibleVehicleIds.remove(vehicleId)) {
                            emitOffline(removedLocation.update)
                        }
                    }
                    assignmentFences[vehicleId] = fence.copy(isEnded = true, endedAt = endedAt)
                }
            }
        }
    }

    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod") // Branches model assignment-fence states atomically.
    override fun markOffline(
        vehicleId: Int,
        assignmentId: Int,
    ): Boolean =
        synchronized(stateLock) {
            sweepExpiredAssignmentFencesLocked()
            val endedAt = Instant.now()
            val fence = assignmentFences[vehicleId]
            when {
                fence != null && assignmentId < fence.assignmentId -> {
                    false
                }

                fence != null && assignmentId == fence.assignmentId && fence.isEnded -> {
                    true
                }

                else -> {
                    val latest = latestByVehicleId[vehicleId]?.update
                    if (latest?.assignmentId != null && latest.assignmentId > assignmentId) {
                        assignmentFences[vehicleId] = AssignmentFence(latest.assignmentId, isEnded = false)
                        false
                    } else {
                        val isLatestForAssignment =
                            latest?.assignmentId == null || latest.assignmentId == assignmentId
                        if (latest != null && isLatestForAssignment) {
                            val removedLocation = latestByVehicleId.remove(vehicleId)
                            if (removedLocation != null && publiclyVisibleVehicleIds.remove(vehicleId)) {
                                emitOffline(removedLocation.update)
                            }
                        }
                        assignmentFences[vehicleId] =
                            AssignmentFence(assignmentId, isEnded = true, endedAt = endedAt)
                        true
                    }
                }
            }
        }

    internal fun sweepExpiredAssignmentFences() {
        synchronized(stateLock) { sweepExpiredAssignmentFencesLocked() }
    }

    internal fun pruneExpiredAssignmentFences(now: Instant) {
        synchronized(stateLock) { pruneExpiredAssignmentFencesLocked(now) }
    }

    internal fun sweepStaleLocations(nowMillis: Long = System.currentTimeMillis()) {
        synchronized(stateLock) { sweepStaleLocationsLocked(nowMillis) }
    }

    internal fun indexedNearbyCellCount(): Int = synchronized(stateLock) { subscribersByCell.size }

    override fun lastKnownLocation(vehicleId: Int): VehicleLocationUpdate? =
        synchronized(stateLock) {
            sweepStaleLocationsLocked(System.currentTimeMillis())
            latestByVehicleId[vehicleId]?.update
        }

    override fun subscribeNear(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): Flow<VehicleLiveEvent> =
        flow {
            val feed = openNearby(latitude, longitude, radiusKm)
            try {
                feed.events.collect { event -> emit(event) }
            } finally {
                feed.close()
            }
        }

    override fun openNearby(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): NearbyLocationFeed {
        val cells = cellIndexer.cellsWithin(latitude, longitude, radiusKm).toSet()
        if (cells.isEmpty()) return NearbyLocationFeed(emptyList(), emptyFlow())

        val (snapshot, subscriber) =
            synchronized(stateLock) {
                sweepStaleLocationsLocked(System.currentTimeMillis())
                val updates = snapshotNearLocked(latitude, longitude, radiusKm, cells)
                val nearbySubscriber =
                    NearbySubscriber(
                        latitude = latitude,
                        longitude = longitude,
                        radiusKm = radiusKm,
                        cells = cells,
                        initialVehicleIds = updates.map(VehicleLocationUpdate::vehicleId),
                    )
                cells.forEach { cell ->
                    subscribersByCell.getOrPut(cell) { HashSet() }.add(nearbySubscriber)
                }
                updates to nearbySubscriber
            }

        val eventFlow =
            flow { emitAll(subscriber.pending.asFlow()) }
                .onCompletion { removeSubscriber(subscriber) }
        return NearbyLocationFeed(snapshot, eventFlow) { removeSubscriber(subscriber) }
    }

    override fun snapshotNear(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): List<VehicleLocationUpdate> {
        val cells = cellIndexer.cellsWithin(latitude, longitude, radiusKm).toSet()
        if (cells.isEmpty()) return emptyList()

        return synchronized(stateLock) {
            sweepStaleLocationsLocked(System.currentTimeMillis())
            snapshotNearLocked(latitude, longitude, radiusKm, cells)
        }
    }

    private fun snapshotNearLocked(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        cells: Set<CellId>,
    ): List<VehicleLocationUpdate> {
        val candidates =
            latestByVehicleId.values
                .asSequence()
                .map(PublishedLocation::update)
                .filter { update ->
                    update.sharesLocationWithCustomers &&
                        update.cellId in cells &&
                        haversineKm(
                            lat1 = latitude,
                            lon1 = longitude,
                            lat2 = update.latitude,
                            lon2 = update.longitude,
                        ) <= radiusKm
                }
        return candidates.toList()
    }

    private fun removeSubscriber(subscriber: NearbySubscriber) {
        synchronized(stateLock) {
            if (!subscriber.pending.close()) return@synchronized

            subscriber.cells.forEach { cell ->
                val cellSubscribers = subscribersByCell[cell] ?: return@forEach
                cellSubscribers.remove(subscriber)
                if (cellSubscribers.isEmpty()) subscribersByCell.remove(cell)
            }
        }
    }

    private fun emitPosition(
        update: VehicleLocationUpdate,
        previousCell: CellId?,
    ) {
        val event = VehicleLiveEvent.Position(update)
        notifySubscribers(update.cellId, event)
        if (previousCell != null && previousCell != update.cellId) notifySubscribers(previousCell, event)
    }

    private fun emitOffline(update: VehicleLocationUpdate) {
        notifySubscribers(update.cellId, VehicleLiveEvent.Offline(update.vehicleId))
    }

    private fun notifySubscribers(
        cell: CellId,
        event: VehicleLiveEvent,
    ) {
        subscribersByCell[cell]?.forEach { subscriber -> deliver(subscriber, event) }
    }

    private fun deliver(
        subscriber: NearbySubscriber,
        event: VehicleLiveEvent,
    ) {
        when (event) {
            is VehicleLiveEvent.Position -> {
                deliverPosition(subscriber, event)
            }

            is VehicleLiveEvent.Offline -> {
                deliverOffline(subscriber, event)
            }
        }
    }

    private fun deliverPosition(
        subscriber: NearbySubscriber,
        event: VehicleLiveEvent.Position,
    ) {
        val update = event.update
        val isWithinRadius =
            haversineKm(
                lat1 = subscriber.latitude,
                lon1 = subscriber.longitude,
                lat2 = update.latitude,
                lon2 = update.longitude,
            ) <= subscriber.radiusKm

        if (isWithinRadius) {
            val previousKey =
                subscriber.lastForwardedUpdateByVehicle
                    .put(update.vehicleId, update.idempotencyKey)
            subscriber.visibleVehicleIds.add(update.vehicleId)
            if (previousKey != update.idempotencyKey) subscriber.pending.offer(event)
        } else {
            subscriber.lastForwardedUpdateByVehicle.remove(update.vehicleId)
            if (subscriber.visibleVehicleIds.remove(update.vehicleId)) {
                subscriber.pending.offer(VehicleLiveEvent.Offline(update.vehicleId))
            }
        }
    }

    private fun deliverOffline(
        subscriber: NearbySubscriber,
        event: VehicleLiveEvent.Offline,
    ) {
        subscriber.lastForwardedUpdateByVehicle.remove(event.vehicleId)
        if (subscriber.visibleVehicleIds.remove(event.vehicleId)) subscriber.pending.offer(event)
    }

    private fun sweepExpiredAssignmentFencesLocked() {
        val nowMillis = System.currentTimeMillis()
        if (nowMillis - lastFenceSweepAtMillis < ASSIGNMENT_FENCE_SWEEP_INTERVAL_MILLIS) return
        lastFenceSweepAtMillis = nowMillis
        pruneExpiredAssignmentFencesLocked(Instant.ofEpochMilli(nowMillis))
    }

    private fun pruneExpiredAssignmentFencesLocked(now: Instant) {
        val cutoff = now.minusSeconds(ASSIGNMENT_FENCE_RETENTION_SECONDS)
        assignmentFences.entries.removeIf { (_, fence) ->
            fence.endedAt?.isBefore(cutoff) == true
        }
    }

    private fun sweepStaleLocationsLocked(nowMillis: Long) {
        latestByVehicleId.entries.toList().forEach { (vehicleId, observed) ->
            if (nowMillis - observed.receivedAtMillis < LIVE_LOCATION_STALE_AFTER_MILLIS) return@forEach

            val current = latestByVehicleId[vehicleId]
            val isCurrentAndStale =
                current === observed && nowMillis - observed.receivedAtMillis >= LIVE_LOCATION_STALE_AFTER_MILLIS
            val wasRemoved = isCurrentAndStale && latestByVehicleId.remove(vehicleId, observed)
            if (wasRemoved && publiclyVisibleVehicleIds.remove(vehicleId)) emitOffline(observed.update)
        }
    }
}

private val VehicleLiveEvent.vehicleId: Int
    get() =
        when (this) {
            is VehicleLiveEvent.Position -> update.vehicleId
            is VehicleLiveEvent.Offline -> vehicleId
        }
