package com.mozgobolt.feature.vehicleTracking.routing.dto.request

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val VALID_INSTANT = "2026-01-01T00:00:00Z"

class TelemetryBatchRequestDtoTest {
    private fun point(
        latitude: Double = 47.4979,
        longitude: Double = 19.0402,
        recordedAt: String = VALID_INSTANT,
    ) = TelemetryPointDto(latitude, longitude, recordedAt)

    @Test
    fun `a single well-formed point has no validation errors`() {
        val request = TelemetryBatchRequestDto(points = listOf(point()))

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `an empty batch is rejected`() {
        val request = TelemetryBatchRequestDto(points = emptyList())

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `exactly 100 points is accepted`() {
        val request = TelemetryBatchRequestDto(points = List(100) { point() })

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `101 points is rejected`() {
        val request = TelemetryBatchRequestDto(points = List(101) { point() })

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `latitude at the exact boundaries, 90 and -90, is accepted`() {
        val request = TelemetryBatchRequestDto(points = listOf(point(latitude = 90.0), point(latitude = -90.0)))

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `latitude one hundredth of a degree past either boundary is rejected`() {
        val overNorth = TelemetryBatchRequestDto(points = listOf(point(latitude = 90.01)))
        val overSouth = TelemetryBatchRequestDto(points = listOf(point(latitude = -90.01)))

        assertTrue(overNorth.validate().isNotEmpty())
        assertTrue(overSouth.validate().isNotEmpty())
    }

    @Test
    fun `longitude at the exact boundaries, 180 and -180, is accepted`() {
        val request = TelemetryBatchRequestDto(points = listOf(point(longitude = 180.0), point(longitude = -180.0)))

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `longitude past either boundary is rejected`() {
        val overEast = TelemetryBatchRequestDto(points = listOf(point(longitude = 180.01)))
        val overWest = TelemetryBatchRequestDto(points = listOf(point(longitude = -180.01)))

        assertTrue(overEast.validate().isNotEmpty())
        assertTrue(overWest.validate().isNotEmpty())
    }

    @Test
    fun `NaN latitude is rejected, not silently treated as in-range`() {
        // A range check with `!in a..b` on NaN is famously easy to get backwards — NaN compares
        // false to everything, so this is worth locking in explicitly rather than assuming.
        val request = TelemetryBatchRequestDto(points = listOf(point(latitude = Double.NaN)))

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `infinite latitude or longitude is rejected`() {
        val infiniteLat = TelemetryBatchRequestDto(points = listOf(point(latitude = Double.POSITIVE_INFINITY)))
        val infiniteLon = TelemetryBatchRequestDto(points = listOf(point(longitude = Double.NEGATIVE_INFINITY)))

        assertTrue(infiniteLat.validate().isNotEmpty())
        assertTrue(infiniteLon.validate().isNotEmpty())
    }

    @Test
    fun `a non-ISO-8601 recordedAt is rejected`() {
        val request = TelemetryBatchRequestDto(points = listOf(point(recordedAt = "not-a-timestamp")))

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a recordedAt missing its timezone offset is rejected`() {
        // Instant.parse requires an offset (e.g. trailing Z) — a bare local-looking timestamp
        // must not silently parse as something else.
        val request = TelemetryBatchRequestDto(points = listOf(point(recordedAt = "2026-01-01T00:00:00")))

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `every violation in a batch is reported, not just the first`() {
        val request =
            TelemetryBatchRequestDto(
                points =
                    listOf(
                        point(latitude = 91.0),
                        point(longitude = 181.0),
                        point(recordedAt = "garbage"),
                    ),
            )

        assertEquals(3, request.validate().size)
    }

    @Test
    fun `a valid point among invalid ones doesn't mask the others' errors`() {
        val request =
            TelemetryBatchRequestDto(
                points = listOf(point(), point(latitude = 91.0), point()),
            )

        assertEquals(1, request.validate().size)
    }
}
