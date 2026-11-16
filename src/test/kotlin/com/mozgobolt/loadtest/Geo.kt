/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import com.mozgobolt.feature.vehicleTracking.domain.EARTH_RADIUS_KM
import com.mozgobolt.feature.vehicleTracking.domain.haversineKm
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val DEGREES_IN_CIRCLE = 360.0
private const val COORDINATE_DECIMALS_SCALE = 1_000_000.0

/** Great-circle distance, using the same formula the server applies to its own radius checks. */
internal fun GeoPoint.distanceKmTo(other: GeoPoint): Double =
    haversineKm(latitude, longitude, other.latitude, other.longitude)

/** The point reached by travelling [distanceKm] along an initial [bearingDegrees] (0 = north). */
internal fun GeoPoint.movedBy(
    distanceKm: Double,
    bearingDegrees: Double,
): GeoPoint {
    val angularDistance = distanceKm / EARTH_RADIUS_KM
    val bearing = Math.toRadians(bearingDegrees)
    val latitudeRad = Math.toRadians(latitude)
    val destinationLatitude =
        asin(sin(latitudeRad) * cos(angularDistance) + cos(latitudeRad) * sin(angularDistance) * cos(bearing))
    val destinationLongitude =
        Math.toRadians(longitude) +
            atan2(
                sin(bearing) * sin(angularDistance) * cos(latitudeRad),
                cos(angularDistance) - sin(latitudeRad) * sin(destinationLatitude),
            )
    return GeoPoint(
        latitude = Math.toDegrees(destinationLatitude),
        longitude = (Math.toDegrees(destinationLongitude) + 540.0) % DEGREES_IN_CIRCLE - 180.0,
    )
}

/**
 * Linear interpolation, adequate at city scale. Used only to place synthetic vehicles between waypoints.
 */
internal fun GeoPoint.lerp(
    other: GeoPoint,
    fraction: Double,
): GeoPoint =
    GeoPoint(
        latitude = latitude + (other.latitude - latitude) * fraction,
        longitude = longitude + (other.longitude - longitude) * fraction,
    )

/**
 * Rounds to the six decimals used on the wire, so the client measures distances from exactly the
 * centre the server received instead of from a slightly different unrounded one.
 */
internal fun GeoPoint.roundedToWirePrecision(): GeoPoint =
    GeoPoint(
        latitude = (latitude * COORDINATE_DECIMALS_SCALE).roundToLong() / COORDINATE_DECIMALS_SCALE,
        longitude = (longitude * COORDINATE_DECIMALS_SCALE).roundToLong() / COORDINATE_DECIMALS_SCALE,
    )

/**
 * A point scattered around this one with a Rayleigh-distributed distance, the radial law of an
 * isotropic 2-D Gaussian: dense near the centre, thinning out like a real city.
 */
internal fun GeoPoint.scatteredBy(
    sigmaKm: Double,
    random: Random,
): GeoPoint =
    movedBy(
        distanceKm = sigmaKm * sqrt(-2.0 * ln(1.0 - random.nextDouble())),
        bearingDegrees = random.nextDouble(DEGREES_IN_CIRCLE),
    )
