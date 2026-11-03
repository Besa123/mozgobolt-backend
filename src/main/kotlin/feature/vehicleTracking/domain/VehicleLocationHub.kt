/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.domain

import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLiveEvent
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import kotlinx.coroutines.flow.Flow

/**
 * Fast path: the latest known location per vehicle, broadcast in-memory with no database
 * round-trip. Durable history is a separate, slower path ([VehicleLocationRepository] +
 * `VehicleLocationBatchWriter`) — this hub is deliberately lossy across a server restart.
 *
 * A Facade over geospatial partitioning on the read side: [subscribeNear]/[snapshotNear] callers
 * ask "what's near this point" in plain lat/lon and never know a [CellIndexer] is involved —
 * that's an implementation detail, swappable without touching routes or this feature's DI wiring
 * beyond `di/`. [publish] takes the [com.mozgobolt.feature.vehicleTracking.domain.model.CellId]
 * that's already on the update (computed once, at ingestion) rather than recomputing it here.
 */
interface VehicleLocationHub {
    /** Publishes a location update to the in-memory live-location state. */
    fun publish(update: VehicleLocationUpdate)

    /**
     * Publishes a point only if it belongs to the vehicle's current assignment session.
     * Implementations that do not track assignment sessions may use the default behavior.
     */
    fun publishIfCurrent(update: VehicleLocationUpdate): Boolean {
        publish(update)
        return true
    }

    /**
     * Signals that [vehicleId]'s driving session just ended: removes it from nearby snapshots
     * (so it stops being reported as "currently here" — and so this hub doesn't
     * keep an ever-growing entry per vehicle that has *ever* driven, only ones currently active)
     * and emits [VehicleLiveEvent.Offline] to anyone subscribed near its last known position, so
     * an already-connected viewer removes it immediately rather than seeing a frozen, stale pin
     * until their connection happens to reconnect. A vehicle with no last-known location (never
     * published, or already marked offline) is a safe no-op.
     */
    fun markOffline(vehicleId: Int)

    /**
     * Assignment-scoped form used by shift mutations. Implementations can ignore an offline
     * notification that arrives after a newer assignment has already published.
     */
    fun markOffline(
        vehicleId: Int,
        assignmentId: Int,
    ): Boolean {
        markOffline(vehicleId)
        return true
    }

    /** The vehicle's own last published location, regardless of anyone's viewport — `null` if it
     * has never published, or if its session has since ended (see [markOffline]). Used to
     * sanity-check the *next* point against, not for display. */
    fun lastKnownLocation(vehicleId: Int): VehicleLocationUpdate?

    /** Every position or offline event for a vehicle currently within [radiusKm] of the given
     * point (or that was, at the moment it went offline). */
    fun subscribeNear(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): Flow<VehicleLiveEvent>

    /** The latest known location of every vehicle currently within [radiusKm] of the given point. */
    fun snapshotNear(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): List<VehicleLocationUpdate>
}
