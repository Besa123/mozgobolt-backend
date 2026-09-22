package com.mozgobolt.feature.vehicleTracking.domain

import com.mozgobolt.feature.vehicleTracking.domain.model.CellId
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import java.time.Instant
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpsPlausibilityTest {
    private val budapest = 47.4979 to 19.0402
    private val debrecen = 47.5316 to 21.6273 // ~180km away

    private fun previousAt(
        latitude: Double,
        longitude: Double,
        recordedAt: Instant,
    ) = VehicleLocationUpdate(
        vehicleId = 1,
        vendorUserId = 1,
        latitude = latitude,
        longitude = longitude,
        recordedAt = recordedAt,
        cellId = CellId("irrelevant-for-this-test"),
    )

    @Test
    fun `a vehicle's very first ping has nothing to compare against, so it's always plausible`() {
        val (lat, lon) = budapest

        assertTrue(GpsPlausibility.isPlausibleMovement(null, lat, lon, Instant.now()))
    }

    @Test
    fun `staying still is always plausible, regardless of elapsed time`() {
        val (lat, lon) = budapest
        val previous = previousAt(lat, lon, Instant.now())

        assertTrue(GpsPlausibility.isPlausibleMovement(previous, lat, lon, previous.recordedAt.plusSeconds(3600)))
    }

    @Test
    fun `ordinary highway-speed movement is plausible`() {
        val (fromLat, fromLon) = budapest
        val (toLat, toLon) = debrecen
        val distanceKm = haversineKm(fromLat, fromLon, toLat, toLon)
        val previous = previousAt(fromLat, fromLon, Instant.now())
        // 180km in 2 hours = 90 km/h — comfortably below the plausibility ceiling.
        val elapsedSeconds = (distanceKm / 90.0 * 3600).toLong()

        val result =
            GpsPlausibility.isPlausibleMovement(previous, toLat, toLon, previous.recordedAt.plusSeconds(elapsedSeconds))

        assertTrue(result)
    }

    @Test
    fun `a multi-hundred-km jump in a few seconds is rejected as a GPS artifact`() {
        val (fromLat, fromLon) = budapest
        val (toLat, toLon) = debrecen
        val previous = previousAt(fromLat, fromLon, Instant.now())

        // ~180km in 5 seconds implies a wildly impossible speed — the classic tunnel-exit jump.
        val result =
            GpsPlausibility.isPlausibleMovement(previous, toLat, toLon, previous.recordedAt.plusSeconds(5))

        assertFalse(result)
    }

    @Test
    fun `a speed exactly at the plausibility ceiling is accepted`() {
        val (fromLat, fromLon) = budapest
        val (toLat, toLon) = debrecen
        val distanceKm = haversineKm(fromLat, fromLon, toLat, toLon)
        val previous = previousAt(fromLat, fromLon, Instant.now())
        // Round the elapsed time UP (not truncate) so the implied speed is guaranteed <= the
        // ceiling rather than landing a fraction of a millisecond over it by rounding error.
        val elapsedMillis = ceil(distanceKm / GpsPlausibility.MAX_PLAUSIBLE_SPEED_KMH * 3_600_000).toLong()

        val result =
            GpsPlausibility.isPlausibleMovement(previous, toLat, toLon, previous.recordedAt.plusMillis(elapsedMillis))

        assertTrue(result)
    }

    @Test
    fun `a speed just past the plausibility ceiling is rejected`() {
        val (fromLat, fromLon) = budapest
        val (toLat, toLon) = debrecen
        val distanceKm = haversineKm(fromLat, fromLon, toLat, toLon)
        val previous = previousAt(fromLat, fromLon, Instant.now())
        // Slightly less elapsed time than the ceiling requires -> speed just over the limit.
        val elapsedMillis = (distanceKm / GpsPlausibility.MAX_PLAUSIBLE_SPEED_KMH * 3_600_000 * 0.99).toLong()

        val result =
            GpsPlausibility.isPlausibleMovement(previous, toLat, toLon, previous.recordedAt.plusMillis(elapsedMillis))

        assertFalse(result)
    }

    @Test
    fun `a large jump at or before the previous timestamp is still rejected, ordering ambiguity is not a free pass`() {
        // Regression: this used to accept ANY distance when elapsed time was non-positive, on the
        // reasoning that "nothing meaningful to divide by" means nothing to reject on either — but
        // a vehicle cannot really be ~180km from where it just was, regardless of which of two
        // ambiguously-ordered points came "first". Ordering ambiguity only excuses jitter-scale
        // noise, never an outright teleport.
        val (fromLat, fromLon) = budapest
        val (toLat, toLon) = debrecen
        val previous = previousAt(fromLat, fromLon, Instant.now())

        val sameTimestamp = GpsPlausibility.isPlausibleMovement(previous, toLat, toLon, previous.recordedAt)
        val earlierTimestamp =
            GpsPlausibility.isPlausibleMovement(previous, toLat, toLon, previous.recordedAt.minusSeconds(60))

        assertFalse(sameTimestamp, "a same-instant 180km jump is physically impossible, not just ambiguous ordering")
        assertFalse(earlierTimestamp, "an out-of-order 180km jump is still physically impossible")
    }

    @Test
    fun `a small jump at or before the previous timestamp is accepted as ordering-ambiguous jitter`() {
        val (lat, lon) = budapest
        val previous = previousAt(lat, lon, Instant.now())
        // A few tens of meters north — well within jitter tolerance.
        val nearbyLat = lat + 0.0002

        val sameTimestamp = GpsPlausibility.isPlausibleMovement(previous, nearbyLat, lon, previous.recordedAt)
        val earlierTimestamp =
            GpsPlausibility.isPlausibleMovement(previous, nearbyLat, lon, previous.recordedAt.minusSeconds(60))

        assertTrue(sameTimestamp, "an out-of-order duplicate timestamp with jitter-scale distance is a batching quirk")
        assertTrue(earlierTimestamp, "an out-of-order earlier timestamp with jitter-scale distance is a batching quirk")
    }

    @Test
    fun `a jitter distance exactly at the ceiling is accepted when ordering is ambiguous`() {
        val (lat, lon) = budapest
        val previous = previousAt(lat, lon, Instant.now())
        // Move due north by exactly the jitter ceiling (111.32km per degree of latitude).
        val degreesForCeiling = GpsPlausibility.MAX_PLAUSIBLE_JITTER_DISTANCE_KM / 111.32
        val candidateLat = lat + degreesForCeiling

        val result = GpsPlausibility.isPlausibleMovement(previous, candidateLat, lon, previous.recordedAt)

        assertTrue(result)
    }

    @Test
    fun `a jitter distance just past the ceiling is rejected when ordering is ambiguous`() {
        val (lat, lon) = budapest
        val previous = previousAt(lat, lon, Instant.now())
        val degreesJustOver = (GpsPlausibility.MAX_PLAUSIBLE_JITTER_DISTANCE_KM + 0.01) / 111.32
        val candidateLat = lat + degreesJustOver

        val result = GpsPlausibility.isPlausibleMovement(previous, candidateLat, lon, previous.recordedAt)

        assertFalse(result)
    }

    @Test
    fun `an exact duplicate point, same coordinate and same instant, is plausible`() {
        // The intersection of the two guard clauses (zero distance AND zero elapsed time) —
        // must not divide by zero or otherwise misbehave.
        val (lat, lon) = budapest
        val previous = previousAt(lat, lon, Instant.now())

        val result = GpsPlausibility.isPlausibleMovement(previous, lat, lon, previous.recordedAt)

        assertTrue(result)
    }
}
