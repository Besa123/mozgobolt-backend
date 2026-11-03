/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.savedLocation.routing.dto.request

import com.mozgobolt.feature.savedLocation.domain.model.SavedLocationConstraints
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoBounds
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreateSavedLocationRequestDtoTest {
    @Test
    fun `inclusive coordinate and radius boundaries are valid`() {
        assertTrue(
            request(
                latitude = GeoBounds.MIN_LATITUDE,
                longitude = GeoBounds.MIN_LONGITUDE,
                radiusKm = SavedLocationConstraints.MIN_RADIUS_KM,
            ).validate().isEmpty(),
        )
        assertTrue(
            request(
                latitude = GeoBounds.MAX_LATITUDE,
                longitude = GeoBounds.MAX_LONGITUDE,
                radiusKm = SavedLocationConstraints.MAX_RADIUS_KM,
            ).validate().isEmpty(),
        )
    }

    @Test
    fun `NaN and infinities are invalid coordinates and radii`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { value ->
            assertFalse(request(latitude = value).validate().isEmpty(), "latitude $value must be rejected")
            assertFalse(request(longitude = value).validate().isEmpty(), "longitude $value must be rejected")
            assertFalse(request(radiusKm = value).validate().isEmpty(), "radius $value must be rejected")
        }
    }

    private fun request(
        latitude: Double = 47.4979,
        longitude: Double = 19.0402,
        radiusKm: Double = 2.5,
    ) = CreateSavedLocationRequestDto(
        label = "Home",
        latitude = latitude,
        longitude = longitude,
        radiusKm = radiusKm,
    )
}
