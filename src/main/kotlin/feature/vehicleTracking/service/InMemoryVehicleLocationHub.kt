package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.feature.vehicleTracking.domain.CellIndexer
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.model.CellId
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLiveEvent
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import java.util.concurrent.ConcurrentHashMap

private const val BROADCAST_BUFFER_CAPACITY = 64

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
 * distinct-cells-held-in-memory-forever. `internal`, not `private`, only so that sweeper can be
 * constructed with a reference to it.
 */
class InMemoryVehicleLocationHub(
    private val cellIndexer: CellIndexer,
) : VehicleLocationHub {
    private val latestByVehicleId = ConcurrentHashMap<Int, VehicleLocationUpdate>()
    internal val flowsByCell = ConcurrentHashMap<CellId, MutableSharedFlow<VehicleLiveEvent>>()
    private val allFlow = newBroadcastFlow()

    override fun publish(update: VehicleLocationUpdate) {
        latestByVehicleId[update.vehicleId] = update

        val event = VehicleLiveEvent.Position(update)
        // No-op if nobody has ever subscribed near this cell — nothing to create a flow for yet.
        flowsByCell[update.cellId]?.tryEmit(event)
        allFlow.tryEmit(event)
    }

    override fun markOffline(vehicleId: Int) {
        val last = latestByVehicleId.remove(vehicleId) ?: return

        val event = VehicleLiveEvent.Offline(vehicleId)
        flowsByCell[last.cellId]?.tryEmit(event)
        allFlow.tryEmit(event)
    }

    override fun lastKnownLocation(vehicleId: Int): VehicleLocationUpdate? = latestByVehicleId[vehicleId]

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

    override fun subscribeAll(): Flow<VehicleLiveEvent> = allFlow.asSharedFlow()

    override fun snapshotNear(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): List<VehicleLocationUpdate> {
        val cells = cellIndexer.cellsWithin(latitude, longitude, radiusKm)
        return latestByVehicleId.values.filter { it.cellId in cells }
    }

    override fun snapshotAll(): List<VehicleLocationUpdate> = latestByVehicleId.values.toList()

    private fun newBroadcastFlow() =
        MutableSharedFlow<VehicleLiveEvent>(
            replay = 0,
            extraBufferCapacity = BROADCAST_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
}
