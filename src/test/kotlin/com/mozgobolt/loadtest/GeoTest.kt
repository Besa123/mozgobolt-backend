/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {
    private val budapest = GeoPoint(47.4979, 19.0402)

    @Test
    fun `movedBy lands at the requested great-circle distance in every direction`() {
        (0 until 360 step 15).forEach { bearing ->
            assertEquals(
                5.0,
                budapest.distanceKmTo(budapest.movedBy(5.0, bearing.toDouble())),
                absoluteTolerance = 1e-6,
                message = "bearing $bearing",
            )
        }
    }

    @Test
    fun `movedBy north increases latitude and east increases longitude`() {
        assertTrue(budapest.movedBy(10.0, 0.0).latitude > budapest.latitude)
        assertTrue(budapest.movedBy(10.0, 90.0).longitude > budapest.longitude)
    }

    @Test
    fun `scatteredBy is deterministic and concentrates near the centre`() {
        fun scatter(seed: Int) = Random(seed).let { random -> List(4_000) { budapest.scatteredBy(4.0, random) } }

        val scattered = scatter(1)

        assertEquals(scattered, scatter(1))
        val withinOneSigma = scattered.count { budapest.distanceKmTo(it) <= 4.0 }
        assertTrue(
            withinOneSigma in 1_450..1_700,
            "a Rayleigh law puts ~39% (1573 of 4000) within one sigma, got $withinOneSigma",
        )
    }

    @Test
    fun `wire precision rounding keeps six decimals and moves the point by well under a metre`() {
        val point = GeoPoint(47.49791234567, 19.04021234567)

        val rounded = point.roundedToWirePrecision()

        assertEquals(GeoPoint(47.497912, 19.040212), rounded)
        assertTrue(point.distanceKmTo(rounded) < 0.001)
    }
}
