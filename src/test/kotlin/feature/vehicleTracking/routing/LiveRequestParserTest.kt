package com.mozgobolt.feature.vehicleTracking.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LiveRequestParserTest {
    @Test
    fun `no scope at all defaults to nearby, same as scope=nearby explicitly`() {
        val implicit = parseLiveRequest(scope = null, lat = "47.4979", lon = "19.0402", radiusKm = null)
        val explicit = parseLiveRequest(scope = "nearby", lat = "47.4979", lon = "19.0402", radiusKm = null)

        assertEquals(implicit, explicit)
        assertIs<LiveRequest.Nearby>(implicit)
    }

    @Test
    fun `scope=nearby with valid coordinates and no radius falls back to the default radius`() {
        val result = parseLiveRequest(scope = "nearby", lat = "47.4979", lon = "19.0402", radiusKm = null)

        val nearby = assertIs<LiveRequest.Nearby>(result)
        assertEquals(47.4979, nearby.latitude)
        assertEquals(19.0402, nearby.longitude)
        assertEquals(DEFAULT_RADIUS_KM, nearby.radiusKm)
    }

    @Test
    fun `scope=nearby with an explicit radius uses it`() {
        val result = parseLiveRequest(scope = "nearby", lat = "47.4979", lon = "19.0402", radiusKm = "10")

        assertEquals(10.0, assertIs<LiveRequest.Nearby>(result).radiusKm)
    }

    @Test
    fun `scope=all ignores lat, lon and radius entirely`() {
        val withJunkParams = parseLiveRequest(scope = "all", lat = "not-a-number", lon = null, radiusKm = "-5")
        val withNoParams = parseLiveRequest(scope = "all", lat = null, lon = null, radiusKm = null)

        assertEquals(LiveRequest.All, withJunkParams)
        assertEquals(LiveRequest.All, withNoParams)
    }

    @Test
    fun `an unrecognized scope is invalid`() {
        val result = parseLiveRequest(scope = "everywhere", lat = null, lon = null, radiusKm = null)

        assertIs<LiveRequest.Invalid>(result)
    }

    @Test
    fun `missing lat is invalid`() {
        val result = parseLiveRequest(scope = "nearby", lat = null, lon = "19.0402", radiusKm = null)

        assertIs<LiveRequest.Invalid>(result)
    }

    @Test
    fun `missing lon is invalid`() {
        val result = parseLiveRequest(scope = "nearby", lat = "47.4979", lon = null, radiusKm = null)

        assertIs<LiveRequest.Invalid>(result)
    }

    @Test
    fun `a non-numeric lat is invalid, same as a missing one`() {
        val result = parseLiveRequest(scope = "nearby", lat = "not-a-number", lon = "19.0402", radiusKm = null)

        assertIs<LiveRequest.Invalid>(result)
    }

    @Test
    fun `a latitude outside the -90 to 90 range is invalid`() {
        val result = parseLiveRequest(scope = "nearby", lat = "90.1", lon = "19.0402", radiusKm = null)

        assertIs<LiveRequest.Invalid>(result)
    }

    @Test
    fun `a longitude outside the -180 to 180 range is invalid`() {
        val result = parseLiveRequest(scope = "nearby", lat = "47.4979", lon = "180.1", radiusKm = null)

        assertIs<LiveRequest.Invalid>(result)
    }

    @Test
    fun `a zero radius is invalid`() {
        val result = parseLiveRequest(scope = "nearby", lat = "47.4979", lon = "19.0402", radiusKm = "0")

        assertIs<LiveRequest.Invalid>(result)
    }

    @Test
    fun `a negative radius is invalid`() {
        val result = parseLiveRequest(scope = "nearby", lat = "47.4979", lon = "19.0402", radiusKm = "-1")

        assertIs<LiveRequest.Invalid>(result)
    }

    @Test
    fun `a radius over the max is invalid`() {
        val result =
            parseLiveRequest(
                scope = "nearby",
                lat = "47.4979",
                lon = "19.0402",
                radiusKm = (MAX_RADIUS_KM + 0.1).toString(),
            )

        assertIs<LiveRequest.Invalid>(result)
    }

    @Test
    fun `a radius exactly at the max is valid`() {
        val result =
            parseLiveRequest(scope = "nearby", lat = "47.4979", lon = "19.0402", radiusKm = MAX_RADIUS_KM.toString())

        assertIs<LiveRequest.Nearby>(result)
    }
}
