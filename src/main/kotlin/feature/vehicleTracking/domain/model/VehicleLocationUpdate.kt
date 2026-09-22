package com.mozgobolt.feature.vehicleTracking.domain.model

import java.time.Instant

/**
 * [cellId] is computed once, at ingestion, and carried alongside the coordinate from then on —
 * both the fast-path hub and the durable history persist it, rather than each recomputing it (or
 * one of them silently drifting from the other if the indexing scheme ever changes).
 */
data class VehicleLocationUpdate(
    val vehicleId: Int,
    val vendorUserId: Int,
    val latitude: Double,
    val longitude: Double,
    val recordedAt: Instant,
    val cellId: CellId,
    // Which driving session this point was recorded during — always set by real ingestion
    // (VehicleTrackingServiceI), backs the retention purge's session-scoped cutoff. Defaulted to
    // null so existing tests unconcerned with retention/session-linkage don't need updating.
    val assignmentId: Int? = null,
)
