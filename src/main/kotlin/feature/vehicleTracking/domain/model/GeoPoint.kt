package com.mozgobolt.feature.vehicleTracking.domain.model

/**
 * A plain lat/lon pair — used where a coordinate needs to travel as one value rather than two
 * separate parameters, e.g. [com.mozgobolt.feature.vehicleTracking.domain.CellIndexer.centerOf]'s
 * return value.
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)
