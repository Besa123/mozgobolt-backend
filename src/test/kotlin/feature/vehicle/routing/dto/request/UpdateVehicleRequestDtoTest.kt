/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicle.routing.dto.request

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateVehicleRequestDtoTest {
    @Test
    fun `an omitted pictureUrl preserves the current value`() {
        val request = Json.decodeFromString<UpdateVehicleRequestDto>("""{"licensePlate":"ABC-123"}""")

        assertEquals(null, request.pictureUrl)
        assertEquals(false, request.clearPictureUrl)
    }

    @Test
    fun `explicit null pictureUrl also preserves the current value`() {
        val request = Json.decodeFromString<UpdateVehicleRequestDto>("""{"licensePlate":"ABC-123","pictureUrl":null}""")

        assertEquals(null, request.pictureUrl)
        assertEquals(false, request.clearPictureUrl)
    }

    @Test
    fun `clearPictureUrl explicitly clears the current value`() {
        val request =
            Json.decodeFromString<UpdateVehicleRequestDto>(
                """{"licensePlate":"ABC-123","clearPictureUrl":true}""",
            )

        assertEquals(null, request.pictureUrl)
        assertEquals(true, request.clearPictureUrl)
    }

    @Test
    fun `a picture url and clear request cannot be supplied together`() {
        val request =
            Json.decodeFromString<UpdateVehicleRequestDto>(
                """{"licensePlate":"ABC-123","pictureUrl":"https://example.test/vehicle.jpg","clearPictureUrl":true}""",
            )

        assertEquals(false, request.validate().isEmpty())
    }
}
