/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.feature.vehicleTracking.domain.haversineKm
import com.uber.h3core.H3Core
import com.uber.h3core.LengthUnit
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class H3CellIndexerTest {
    private val indexer = H3CellIndexer()

    // Real Hungarian coordinates so "nearby" and "far away" mean something concrete.
    private val budapest = 47.4979 to 19.0402
    private val oneKmNorthOfBudapest = 47.5069 to 19.0402 // ~1km north (1 deg lat ≈ 111km)
    private val debrecen = 47.5316 to 21.6273 // ~180km east of Budapest

    @Test
    fun `cellFor is deterministic for the same coordinates`() {
        val (lat, lon) = budapest

        assertEquals(indexer.cellFor(lat, lon), indexer.cellFor(lat, lon))
    }

    @Test
    fun `cellFor differs for two clearly distinct locations`() {
        val (budapestLat, budapestLon) = budapest
        val (debrecenLat, debrecenLon) = debrecen

        assertNotEquals(indexer.cellFor(budapestLat, budapestLon), indexer.cellFor(debrecenLat, debrecenLon))
    }

    @Test
    fun `cellsWithin always contains the center point's own cell`() {
        val (lat, lon) = budapest

        val cells = indexer.cellsWithin(lat, lon, radiusKm = 5.0)

        assertTrue(indexer.cellFor(lat, lon) in cells)
    }

    @Test
    fun `cellsWithin contains a point genuinely within the requested radius`() {
        val (centerLat, centerLon) = budapest
        val (nearLat, nearLon) = oneKmNorthOfBudapest

        val cells = indexer.cellsWithin(centerLat, centerLon, radiusKm = 5.0)

        assertTrue(indexer.cellFor(nearLat, nearLon) in cells)
    }

    @Test
    fun `cellsWithin conservatively covers points near the radius in Hungary and global edge cases`() {
        val centers =
            listOf(
                budapest,
                47.6817 to 16.5845,
                48.3949 to 21.6555,
                0.0 to 179.9,
                89.0 to 45.0,
            )

        centers.forEach { (centerLat, centerLon) ->
            listOf(1.0, 5.0, 25.0).forEach { radiusKm ->
                assertRadiusCoverage(indexer, centerLat, centerLon, radiusKm)
            }
        }
    }

    @Test
    fun `cellsWithin never misses a point inside the radius anywhere in Hungary`() {
        // A seeded fuzz rather than a handful of hand-picked bearings: ring counts are sized from an
        // average edge length, so this guards the "superset, never a subset" contract across cell
        // shapes, orientations and radii (a live-load run measured the true minimum at 3 rings for
        // 5km where 4 are used, so the margin is real but not unlimited).
        val random = Random(2026)

        listOf(1.0, 2.0, 5.0, 10.0, 15.0, 25.0).forEach { radiusKm ->
            repeat(3_000) {
                val centerLat = random.nextDouble(46.0, 48.5)
                val centerLon = random.nextDouble(16.0, 22.5)
                val (latitude, longitude) =
                    destinationPoint(centerLat, centerLon, radiusKm * 0.999, random.nextDouble(360.0))

                assertTrue(
                    indexer.cellFor(latitude, longitude) in indexer.cellsWithin(centerLat, centerLon, radiusKm),
                    "missed an in-radius point: center=($centerLat,$centerLon) radius=$radiusKm",
                )
            }
        }
    }

    @Test
    fun `cellsWithin excludes a point far outside the requested radius`() {
        val (centerLat, centerLon) = budapest
        val (farLat, farLon) = debrecen

        val cells = indexer.cellsWithin(centerLat, centerLon, radiusKm = 5.0)

        assertTrue(indexer.cellFor(farLat, farLon) !in cells)
    }

    @Test
    fun `cellsWithin grows as the requested radius grows`() {
        val (lat, lon) = budapest

        val small = indexer.cellsWithin(lat, lon, radiusKm = 2.0)
        val large = indexer.cellsWithin(lat, lon, radiusKm = 20.0)

        assertTrue(large.size > small.size)
        assertTrue(small.all { it in large }, "a smaller radius's cells must all still be covered by a larger one")
    }

    @Test
    fun `an unreasonably large radius is bounded rather than exploding`() {
        val (lat, lon) = budapest

        // MAX_RING_COUNT clamps this — the assertion is just that it returns promptly and
        // doesn't attempt to materialize an unbounded number of cells.
        val cells = indexer.cellsWithin(lat, lon, radiusKm = 1_000_000.0)

        assertTrue(cells.isNotEmpty())
        assertTrue(cells.size < 100_000)
    }

    @Test
    fun `a zero or negative radius still returns at least the containing cell, never empty or a crash`() {
        // Route-level validation should reject this before it ever reaches here (radiusKm must
        // be > 0) — this is the defense-in-depth layer (MIN_RING_COUNT), tested directly in case
        // that upstream validation is ever weakened or bypassed by another caller.
        val (lat, lon) = budapest

        val zero = indexer.cellsWithin(lat, lon, radiusKm = 0.0)
        val negative = indexer.cellsWithin(lat, lon, radiusKm = -5.0)

        assertTrue(indexer.cellFor(lat, lon) in zero)
        assertTrue(indexer.cellFor(lat, lon) in negative)
    }

    @Test
    fun `coordinates near the north pole don't crash and stay deterministic`() {
        val nearPole = 89.9 to 45.0

        val cell = indexer.cellFor(nearPole.first, nearPole.second)
        val cells = indexer.cellsWithin(nearPole.first, nearPole.second, radiusKm = 5.0)

        assertEquals(cell, indexer.cellFor(nearPole.first, nearPole.second))
        assertTrue(cell in cells)
    }

    @Test
    fun `points on opposite sides of the antimeridian are still found as neighbors`() {
        // 179.999 and -179.999 longitude are ~0.1km apart in reality despite the numeric jump
        // from +180 to -180 — a naive numeric-range check would wrongly treat them as far apart,
        // but H3's k-ring is geometry-aware, not a longitude-value range check.
        val center = 0.0 to 179.999
        val justAcrossTheLine = 0.0 to -179.999

        val cells = indexer.cellsWithin(center.first, center.second, radiusKm = 1.0)

        assertTrue(indexer.cellFor(justAcrossTheLine.first, justAcrossTheLine.second) in cells)
    }

    @Test
    fun `centerOf returns a point that maps back to the same cell`() {
        val (lat, lon) = budapest
        val cell = indexer.cellFor(lat, lon)

        val center = indexer.centerOf(cell)

        assertEquals(cell, indexer.cellFor(center.latitude, center.longitude))
    }

    @Test
    fun `centerOf is deterministic and differs from an off-center input coordinate`() {
        val (lat, lon) = budapest
        val cell = indexer.cellFor(lat, lon)

        val first = indexer.centerOf(cell)
        val second = indexer.centerOf(cell)

        assertEquals(first, second)
        assertNotEquals(lat, first.latitude)
        assertNotEquals(lon, first.longitude)
    }

    @Test
    fun `resolution 9's average hexagon edge length is around 200m, as vehiclePing's privacy comment claims`() {
        // Confirmed directly against the H3 library rather than trusted from memory — an earlier
        // version of this test and VehiclePingServiceI's comment both claimed ~174m from
        // (incorrect) recollection of H3's published resolution table; H3Core.getHexagonEdgeLengthAvg
        // actually returns ~0.2008km for resolution 9. This test exists precisely so a wrong
        // number like that gets caught, and so a future H3 library upgrade that changed this
        // figure would be caught too, rather than silently drifting from what the comment claims.
        val edgeLengthKm =
            H3Core.newInstance().getHexagonEdgeLengthAvg(
                PING_LOCATION_RESOLUTION_FOR_TEST,
                LengthUnit.km,
            )

        assertTrue(edgeLengthKm in 0.19..0.21, "expected ~0.2008km, got $edgeLengthKm")
    }
}

// Mirrors feature/vehiclePing/service/VehiclePingServiceI.PING_LOCATION_RESOLUTION without a
// cross-feature test dependency on it — this test's whole point is to verify the real-world
// distance behind that resolution number, not to import it as a given.
private const val PING_LOCATION_RESOLUTION_FOR_TEST = 9
private const val EARTH_RADIUS_KM_FOR_TEST = 6371.0088

private fun assertRadiusCoverage(
    indexer: H3CellIndexer,
    centerLat: Double,
    centerLon: Double,
    radiusKm: Double,
) {
    val candidates = indexer.cellsWithin(centerLat, centerLon, radiusKm)
    for (bearing in 0..330 step 30) {
        val (latitude, longitude) =
            destinationPoint(
                centerLat = centerLat,
                centerLon = centerLon,
                distanceKm = radiusKm - 0.01,
                bearingDegrees = bearing.toDouble(),
            )
        val message =
            "missed in-radius point at center=($centerLat,$centerLon), " +
                "radius=$radiusKm, bearing=$bearing"

        assertTrue(haversineKm(centerLat, centerLon, latitude, longitude) < radiusKm)
        assertTrue(indexer.cellFor(latitude, longitude) in candidates, message)
    }
}

private fun destinationPoint(
    centerLat: Double,
    centerLon: Double,
    distanceKm: Double,
    bearingDegrees: Double,
): Pair<Double, Double> {
    val angularDistance = distanceKm / EARTH_RADIUS_KM_FOR_TEST
    val bearing = Math.toRadians(bearingDegrees)
    val latitude = Math.toRadians(centerLat)
    val longitude = Math.toRadians(centerLon)
    val destinationLatitude =
        asin(
            sin(latitude) * cos(angularDistance) +
                cos(latitude) * sin(angularDistance) * cos(bearing),
        )
    val destinationLongitude =
        longitude +
            atan2(
                sin(bearing) * sin(angularDistance) * cos(latitude),
                cos(angularDistance) - sin(latitude) * sin(destinationLatitude),
            )
    val normalizedLongitude = (Math.toDegrees(destinationLongitude) + 540.0) % 360.0 - 180.0
    return Math.toDegrees(destinationLatitude) to normalizedLongitude
}
