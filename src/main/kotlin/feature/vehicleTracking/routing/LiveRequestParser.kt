package com.mozgobolt.feature.vehicleTracking.routing

import com.mozgobolt.feature.vehicleTracking.domain.model.GeoBounds

// "Nearby" is the safe default a client falls into by doing nothing; "everything, nationwide" is
// a name a client has to type on purpose (see [parseLiveRequest]) — never something a client gets
// by *omitting* a parameter, so a forgotten lat/lon can't silently turn into the whole-country
// firehose.
internal const val DEFAULT_RADIUS_KM = 5.0
internal const val MAX_RADIUS_KM = 25.0

internal const val SCOPE_NEARBY = "nearby"
internal const val SCOPE_ALL = "all"

/** What a caller asked `GET /vehicle-tracking/live` for, already validated. */
internal sealed interface LiveRequest {
    data class Nearby(
        val latitude: Double,
        val longitude: Double,
        val radiusKm: Double,
    ) : LiveRequest

    data object All : LiveRequest

    data class Invalid(
        val reason: String,
    ) : LiveRequest
}

/**
 * Pure and deliberately decoupled from Ktor's `ApplicationCall`/`Parameters` — every argument is
 * the raw (possibly absent) query string value, so this is unit-testable the same way a
 * [com.mozgobolt.core.domain.validation.ValidatedRequest] is, without a Ktor test client.
 */
internal fun parseLiveRequest(
    scope: String?,
    lat: String?,
    lon: String?,
    radiusKm: String?,
): LiveRequest =
    when (val resolvedScope = scope ?: SCOPE_NEARBY) {
        SCOPE_ALL -> LiveRequest.All
        SCOPE_NEARBY -> parseNearbyRequest(lat, lon, radiusKm)
        else -> LiveRequest.Invalid("scope must be '$SCOPE_NEARBY' or '$SCOPE_ALL', got '$resolvedScope'")
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
