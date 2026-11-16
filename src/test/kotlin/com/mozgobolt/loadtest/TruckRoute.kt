/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import kotlin.random.Random

private const val DEGREES_IN_CIRCLE = 360.0
private const val DEFAULT_WAYPOINT_COUNT = 8
private const val MIN_ROUTE_RADIUS_SHARE = 0.25
private const val BEARING_JITTER_SHARE = 0.4

/**
 * A closed driving loop parameterised by distance travelled, so a vehicle's position is a pure
 * function of how far it has driven (no integration state to carry between ticks).
 */
internal class TruckRoute(
    private val waypoints: List<GeoPoint>,
) {
    init {
        require(waypoints.size >= 2) { "A route needs at least two waypoints" }
    }

    private val legs: List<GeoPoint> = waypoints + waypoints.first()
    private val legLengthsKm: List<Double> = legs.zipWithNext { from, to -> from.distanceKmTo(to) }
    private val legStartsKm: List<Double> = legLengthsKm.runningFold(0.0, Double::plus)

    val lengthKm: Double = legStartsKm.last()

    /** The position after driving [distanceKm] along the loop; wraps around once the loop is complete. */
    fun positionAt(distanceKm: Double): GeoPoint {
        val offsetKm = distanceKm.mod(lengthKm)
        val leg = legStartsKm.binarySearch(offsetKm).let { if (it >= 0) it else -it - 2 }.coerceAtMost(legs.size - 2)
        val fraction = (offsetKm - legStartsKm[leg]) / legLengthsKm[leg]
        return legs[leg].lerp(legs[leg + 1], fraction)
    }

    companion object {
        /**
         * A star-shaped loop: one waypoint per evenly spread bearing (slightly jittered) at a random
         * radius, which keeps the loop simple (never self-crossing) while still wandering through
         * many different H3 cells and in and out of nearby viewers' radii.
         */
        fun loopAround(
            center: GeoPoint,
            maxRadiusKm: Double,
            random: Random,
            waypointCount: Int = DEFAULT_WAYPOINT_COUNT,
        ): TruckRoute {
            val sector = DEGREES_IN_CIRCLE / waypointCount
            return TruckRoute(
                List(waypointCount) { index ->
                    center.movedBy(
                        distanceKm = maxRadiusKm * random.nextDouble(MIN_ROUTE_RADIUS_SHARE, 1.0),
                        bearingDegrees =
                            sector * (index + random.nextDouble(-BEARING_JITTER_SHARE, BEARING_JITTER_SHARE)),
                    )
                },
            )
        }
    }
}
