package com.mozgobolt.feature.vehicleTracking.domain

import com.mozgobolt.feature.vehicleTracking.domain.model.CellId
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint

/**
 * Strategy seam over whichever spatial indexing scheme buckets coordinates into cells (H3 today
 * — see [com.mozgobolt.feature.vehicleTracking.service.H3CellIndexer]). Nothing outside this
 * feature's `service/` package needs to know a specific scheme is in use; [VehicleLocationHub]
 * depends only on this interface.
 */
interface CellIndexer {
    /** The single cell a point falls into, at this indexer's fixed resolution. */
    fun cellFor(
        latitude: Double,
        longitude: Double,
    ): CellId

    /**
     * Every cell that could contain a point within [radiusKm] of (latitude, longitude) — a
     * superset of the true circle (cells are hexagons/squares, not circles), never a subset, so
     * callers filtering by cell membership alone may over-include near the requested radius but
     * will never miss something genuinely inside it.
     */
    fun cellsWithin(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): Set<CellId>

    /**
     * The cell's own center point — by construction coarser than whatever coordinate mapped into
     * it, since every point inside the cell collapses to this same value. Used to turn "a cell"
     * back into "a coordinate" when a caller deliberately wants the coarsened point rather than
     * the opaque cell id itself (e.g. privacy-safe location coarsening in `feature/vehiclePing`).
     */
    fun centerOf(cellId: CellId): GeoPoint
}
