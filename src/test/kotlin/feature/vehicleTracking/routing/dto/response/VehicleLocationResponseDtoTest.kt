/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.routing.dto.response

import com.mozgobolt.feature.vehicleTracking.domain.model.CellId
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class VehicleLocationResponseDtoTest {
    @Test
    fun `location updates map to the public response without persistence-only fields`() {
        val recordedAt = Instant.parse("2026-10-31T12:34:56Z")
        val update =
            VehicleLocationUpdate(
                vehicleId = 12,
                vendorUserId = 42,
                latitude = 47.5,
                longitude = 19.0,
                recordedAt = recordedAt,
                cellId = CellId("opaque-cell"),
                assignmentId = 99,
            )

        assertEquals(
            VehicleLocationResponseDto(
                vehicleId = 12,
                latitude = 47.5,
                longitude = 19.0,
                recordedAt = recordedAt.toString(),
            ),
            update.toResponseDto(),
        )
    }
}
