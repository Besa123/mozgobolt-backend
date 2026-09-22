package com.mozgobolt.feature.vehicleTracking.domain.model

import java.time.Instant

/** One already-parsed, already-validated GPS reading — the service layer's input shape, decoupled
 * from the wire format ([com.mozgobolt.feature.vehicleTracking.routing.dto.request.TelemetryPointDto]
 * has the same fields but `recordedAt` as an unparsed ISO-8601 string). */
data class TelemetryPoint(
    val latitude: Double,
    val longitude: Double,
    val recordedAt: Instant,
)
