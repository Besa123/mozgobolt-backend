package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.feature.vehicleTracking.domain.CellIndexer
import com.mozgobolt.feature.vehicleTracking.domain.model.CellId
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import com.uber.h3core.H3Core
import com.uber.h3core.LengthUnit
import kotlin.math.ceil

/**
 * H3 (hexagonal hierarchical spatial index) over geohash/quadtree: every cell has 6 neighbors,
 * all equidistant from its center, so a k-ring search (this cell + k rings of neighbors) has no
 * corner case where a point just across a cell edge is missed or double-counted the way a
 * rectangular geohash cell's corner-neighbors (~40% farther than edge-neighbors) can. See
 * https://www.uber.com/blog/h3/.
 */
class H3CellIndexer(
    private val h3: H3Core = H3Core.newInstance(),
    private val resolution: Int = DEFAULT_RESOLUTION,
) : CellIndexer {
    // Average edge length at this resolution; used to size the k-ring for a requested radius.
    // Fixed once per instance (resolution never changes at runtime), not per call.
    private val cellEdgeKm = h3.getHexagonEdgeLengthAvg(resolution, LengthUnit.km)

    override fun cellFor(
        latitude: Double,
        longitude: Double,
    ): CellId = CellId(h3.latLngToCellAddress(latitude, longitude, resolution))

    override fun cellsWithin(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): Set<CellId> {
        val center = h3.latLngToCellAddress(latitude, longitude, resolution)
        val ringCount =
            ceil(radiusKm / cellEdgeKm)
                .toInt()
                .coerceIn(MIN_RING_COUNT, MAX_RING_COUNT)

        return h3.gridDisk(center, ringCount).map(::CellId).toSet()
    }

    override fun centerOf(cellId: CellId): GeoPoint {
        val center = h3.cellToLatLng(cellId.value)
        return GeoPoint(latitude = center.lat, longitude = center.lng)
    }

    companion object {
        // Res 7 ≈ 1.22km average edge — fine enough for a "who's in my neighborhood" query
        // without the k-ring growing huge at the request's max allowed radius (see
        // MAX_RING_COUNT below and VehicleTrackingRoutes' MAX_RADIUS_KM).
        const val DEFAULT_RESOLUTION = 7

        // H3 cell addresses are hex-encoded 64-bit indexes: at most 16 hex characters, ever,
        // regardless of resolution. Referenced by VehicleLocationsTable's column width so that
        // width is tied to this fact, not an independently-guessed number.
        const val CELL_ADDRESS_MAX_LENGTH = 16

        private const val MIN_RING_COUNT = 1

        // Defense in depth, not the primary guard: request-level radius validation
        // (VehicleTrackingRoutes) is what actually keeps ringCount small in practice. This just
        // stops a huge/malformed radiusKm — however it got here — from asking H3 to materialize
        // an unbounded number of cells.
        private const val MAX_RING_COUNT = 50
    }
}
