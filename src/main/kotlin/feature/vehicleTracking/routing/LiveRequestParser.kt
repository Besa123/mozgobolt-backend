/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.routing

import com.mozgobolt.feature.vehicleTracking.domain.model.GeoBounds

// The endpoint intentionally supports only nearby lookups; it has no nationwide feed.
internal const val DEFAULT_RADIUS_KM = 5.0
internal const val MAX_RADIUS_KM = 25.0

internal const val SCOPE_NEARBY = "nearby"

/** What a caller asked `GET /vehicle-tracking/live` for, already validated. */
internal sealed interface LiveRequest {
    data class Nearby(
        val latitude: Double,
        val longitude: Double,
        val radiusKm: Double,
    ) : LiveRequest

    data class Invalid(
        val reason: String,
    ) : LiveRequest
}

/**
 * Pure and deliberately decoupled from Ktor's `ApplicationCall`/`Parameters` — every argument is
 * the raw (possibly absent) query string value, so this is unit-testable without a Ktor test client.
 */
internal fun parseLiveRequest(
    scope: String?,
    lat: String?,
    lon: String?,
    radiusKm: String?,
): LiveRequest {
    val resolvedScope = scope ?: SCOPE_NEARBY
    return if (resolvedScope == SCOPE_NEARBY) {
        parseNearbyRequest(lat, lon, radiusKm)
    } else {
        LiveRequest.Invalid("scope must be '$SCOPE_NEARBY', got '$resolvedScope'")
    }
}

@Suppress("ReturnCount")
private fun parseNearbyRequest(
    lat: String?,
    lon: String?,
    radiusKmRaw: String?,
): LiveRequest {
    val latitude =
        lat?.toDoubleOrNull() ?: return LiveRequest.Invalid("lat is required when scope=nearby (the default)")
    val longitude =
        lon?.toDoubleOrNull() ?: return LiveRequest.Invalid("lon is required when scope=nearby (the default)")
    val radiusKm = radiusKmRaw?.toDoubleOrNull() ?: DEFAULT_RADIUS_KM

    if (latitude !in GeoBounds.MIN_LATITUDE..GeoBounds.MAX_LATITUDE) {
        return LiveRequest.Invalid("lat must be between ${GeoBounds.MIN_LATITUDE} and ${GeoBounds.MAX_LATITUDE}")
    }
    if (longitude !in GeoBounds.MIN_LONGITUDE..GeoBounds.MAX_LONGITUDE) {
        return LiveRequest.Invalid("lon must be between ${GeoBounds.MIN_LONGITUDE} and ${GeoBounds.MAX_LONGITUDE}")
    }
    if (radiusKm <= 0.0 || radiusKm > MAX_RADIUS_KM) {
        return LiveRequest.Invalid("radiusKm must be greater than 0 and at most $MAX_RADIUS_KM")
    }

    return LiveRequest.Nearby(latitude, longitude, radiusKm)
}
