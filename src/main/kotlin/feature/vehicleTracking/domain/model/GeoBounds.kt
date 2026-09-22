package com.mozgobolt.feature.vehicleTracking.domain.model

/** Shared by telemetry ingestion and live-viewport request validation. */
object GeoBounds {
    const val MIN_LATITUDE = -90.0
    const val MAX_LATITUDE = 90.0
    const val MIN_LONGITUDE = -180.0
    const val MAX_LONGITUDE = 180.0
}
