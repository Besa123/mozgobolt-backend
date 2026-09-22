package com.mozgobolt.feature.vehicleTracking.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_KM = 6371.0088

/**
 * Great-circle distance between two points, in kilometers (Haversine formula). Not worth a
 * dependency — this is the one calculation both [GpsPlausibility] and any future "nearest
 * vehicles to me" feature need, and it's a fixed, well-known formula, not something that benefits
 * from a geospatial library the way H3's cell indexing does.
 */
fun haversineKm(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a =
        sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return EARTH_RADIUS_KM * c
}
