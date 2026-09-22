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
    fun publish(update: VehicleLocationUpdate)

    /**
     * Signals that [vehicleId]'s driving session just ended: removes it from both snapshot
     * methods below (so it stops being reported as "currently here" — and so this hub doesn't
     * keep an ever-growing entry per vehicle that has *ever* driven, only ones currently active)
     * and emits [VehicleLiveEvent.Offline] to anyone subscribed near its last known position, so
     * an already-connected viewer removes it immediately rather than seeing a frozen, stale pin
     * until their connection happens to reconnect. A vehicle with no last-known location (never
     * published, or already marked offline) is a safe no-op.
     */
    fun markOffline(vehicleId: Int)

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

    /** Unfiltered — every vehicle, nationwide. An explicit opt-in on the route, never a default. */
    fun subscribeAll(): Flow<VehicleLiveEvent>

    /** The latest known location of every vehicle currently within [radiusKm] of the given point. */
    fun snapshotNear(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): List<VehicleLocationUpdate>

    /** The latest known location of every vehicle, nationwide. */
    fun snapshotAll(): List<VehicleLocationUpdate>
}
