/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class LiveMapStateTest {
    private fun fix(
        vehicleId: Int,
        latitude: Double = 47.0,
    ) = VehicleFix(vehicleId, GeoPoint(latitude, 19.0), Instant.EPOCH)

    @Test
    fun `a location adds the pin and a newer one replaces it`() {
        val map =
            LiveMapState.EMPTY
                .apply(LiveFeedEvent.Location(fix(1, 47.0)))
                .apply(LiveFeedEvent.Location(fix(2)))
                .apply(LiveFeedEvent.Location(fix(1, 47.5)))

        assertEquals(setOf(1, 2), map.vehicles.keys)
        assertEquals(
            47.5,
            map.vehicles
                .getValue(1)
                .position.latitude,
        )
    }

    @Test
    fun `an offline event removes the pin and unknown vehicles are ignored`() {
        val map = LiveMapState.EMPTY.apply(LiveFeedEvent.Location(fix(1)))

        assertEquals(emptySet(), map.apply(LiveFeedEvent.Offline(1)).vehicles.keys)
        assertEquals(setOf(1), map.apply(LiveFeedEvent.Offline(99)).vehicles.keys)
    }

    @Test
    fun `events that do not change the map return the same state`() {
        val map = LiveMapState.EMPTY.apply(LiveFeedEvent.Location(fix(1)))

        assertSame(map, map.apply(LiveFeedEvent.Reconnect))
        assertSame(map, map.apply(LiveFeedEvent.Rejected(429)))
    }
}
