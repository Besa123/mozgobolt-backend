/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import com.mozgobolt.feature.vehicleTracking.service.H3CellIndexer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TruckRouteTest {
    private val budapest = GeoPoint(47.4979, 19.0402)

    @Test
    fun `position wraps around once the loop is complete`() {
        val route = TruckRoute.loopAround(budapest, maxRadiusKm = 9.0, random = Random(1))

        val start = route.positionAt(0.0)
        val afterOneLoop = route.positionAt(route.lengthKm)
        val afterThreeLoopsAndABit = route.positionAt(route.lengthKm * 3 + 1.5)

        assertEquals(0.0, start.distanceKmTo(afterOneLoop), absoluteTolerance = 1e-6)
        assertEquals(0.0, route.positionAt(1.5).distanceKmTo(afterThreeLoopsAndABit), absoluteTolerance = 1e-6)
    }

    @Test
    fun `driving a distance moves the truck by about that distance along the loop`() {
        val route = TruckRoute.loopAround(budapest, maxRadiusKm = 9.0, random = Random(2))

        val stepKm = 0.05
        val steps = (0 until 400).map { route.positionAt(it * stepKm) }

        steps.zipWithNext().forEach { (from, to) ->
            assertTrue(from.distanceKmTo(to) <= stepKm * 1.01, "no teleporting between samples")
        }
    }

    @Test
    fun `loops are deterministic per seed and stay within the requested radius`() {
        val first = TruckRoute.loopAround(budapest, maxRadiusKm = 9.0, random = Random(7))
        val again = TruckRoute.loopAround(budapest, maxRadiusKm = 9.0, random = Random(7))
        val other = TruckRoute.loopAround(budapest, maxRadiusKm = 9.0, random = Random(8))

        assertEquals(first.lengthKm, again.lengthKm)
        assertTrue(first.lengthKm != other.lengthKm)
        (0 until 200).map { first.positionAt(it * first.lengthKm / 200) }.forEach { position ->
            assertTrue(budapest.distanceKmTo(position) <= 9.0 + 1e-6)
        }
    }

    @Test
    fun `single-cell stress profile keeps the whole truck route in one H3 cell`() {
        val spec = WorkloadSpec.load()
        val profile = spec.profiles.getValue("single-cell")
        val indexer = H3CellIndexer()
        val center = singleCellRegion(spec).center
        val cell = indexer.cellFor(center.latitude, center.longitude)
        val route =
            TruckRoute.loopAround(
                center,
                maxRadiusKm = requireNotNull(profile.routeRadiusKm),
                random = Random(11),
            )

        (0..1_000).forEach { sample ->
            val point = route.positionAt(route.lengthKm * sample / 1_000)
            assertEquals(cell, indexer.cellFor(point.latitude, point.longitude), "route sample $sample")
        }
        assertEquals(0.0, profile.cityRadiusKm, "all live-map centers use the H3 cell center")
    }
}
