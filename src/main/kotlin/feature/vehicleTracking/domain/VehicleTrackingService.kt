package com.mozgobolt.feature.vehicleTracking.domain

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryError
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryPoint

interface VehicleTrackingService {
    /**
     * Resolves the vendor's currently active vehicle, then publishes and enqueues each plausible
     * point (see [GpsPlausibility]) against it — a point that fails the plausibility check is
     * dropped silently, not an error, the same way a tunnel's total signal loss is: the vehicle's
     * last good position just holds until a believable one arrives.
     */
    suspend fun ingestTelemetry(
        vendorUserId: Int,
        points: List<TelemetryPoint>,
    ): AppResult<Unit, TelemetryError>
}
