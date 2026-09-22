package com.mozgobolt.feature.vehiclePing.service

import com.mozgobolt.feature.vehiclePing.domain.VehiclePingHub
import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePingNotification
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

private const val BROADCAST_BUFFER_CAPACITY = 64

/**
 * [VehiclePingHub] keyed by vendor user id, using the same per-key `MutableSharedFlow` broadcast
 * shape `InMemoryVehicleLocationHub` already uses for per-cell partitioning — here partitioning
 * by *person* rather than geography, since a ping has exactly one intended recipient rather than
 * every buyer watching a region.
 *
 * `flowsByVendorUserId` grows with the number of *distinct* vendors who have ever connected to
 * the live-pings stream — in practice bounded by this app's actual vendor count, not by ping
 * volume. A vendor's flow is reclaimed once nobody is subscribed to it (they've disconnected and
 * aren't currently listening) by a [com.mozgobolt.core.utility.IdleSharedFlowSweeper] wired over
 * this map (see the vehiclePing DI module) — same mechanism as
 * `InMemoryVehicleLocationHub.flowsByCell`. `internal`, not `private`, only so that sweeper can be
 * constructed with a reference to it.
 */
class InMemoryVehiclePingHub : VehiclePingHub {
    internal val flowsByVendorUserId = ConcurrentHashMap<Int, MutableSharedFlow<VehiclePingNotification>>()

    override fun publish(
        vendorUserId: Int,
        notification: VehiclePingNotification,
    ) {
        // No-op if this vendor has never subscribed — nothing to create a flow for yet, and
        // nothing durable backs a ping, so a vendor who isn't connected right now simply misses
        // it (see VehiclePingHub's kdoc).
        flowsByVendorUserId[vendorUserId]?.tryEmit(notification)
    }

    override fun subscribe(vendorUserId: Int): Flow<VehiclePingNotification> =
        flowsByVendorUserId.computeIfAbsent(vendorUserId) { newBroadcastFlow() }.asSharedFlow()

    private fun newBroadcastFlow() =
        MutableSharedFlow<VehiclePingNotification>(
            replay = 0,
            extraBufferCapacity = BROADCAST_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
}
