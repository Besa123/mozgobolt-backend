/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehiclePing.routing.dto.request

import com.mozgobolt.feature.vehicleTracking.domain.model.GeoBounds
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PingVehicleRequestDtoTest {
    @Test
    fun `inclusive geographic boundaries are valid`() {
        assertTrue(request(GeoBounds.MIN_LATITUDE, GeoBounds.MIN_LONGITUDE).validate().isEmpty())
        assertTrue(request(GeoBounds.MAX_LATITUDE, GeoBounds.MAX_LONGITUDE).validate().isEmpty())
    }

    @Test
    fun `out-of-range non-finite and just-outside coordinates are invalid`() {
        val invalidLatitudes =
            listOf(
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                GeoBounds.MIN_LATITUDE - 0.000001,
                GeoBounds.MAX_LATITUDE + 0.000001,
            )
        val invalidLongitudes =
            listOf(
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                GeoBounds.MIN_LONGITUDE - 0.000001,
                GeoBounds.MAX_LONGITUDE + 0.000001,
            )

        invalidLatitudes.forEach { value ->
            assertFalse(request(latitude = value).validate().isEmpty(), "latitude $value must be rejected")
        }
        invalidLongitudes.forEach { value ->
            assertFalse(request(longitude = value).validate().isEmpty(), "longitude $value must be rejected")
        }
    }

    private fun request(
        latitude: Double = 47.4979,
        longitude: Double = 19.0402,
    ) = PingVehicleRequestDto(latitude = latitude, longitude = longitude)
}
