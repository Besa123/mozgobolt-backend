/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.domain

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryDispatchPlan
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryError
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryPoint

/** Use cases for validating and ingesting vendor vehicle telemetry. */
interface VehicleTrackingService {
    /** Validates and records telemetry, returning the delivery work to perform after commit. */
    suspend fun prepareTelemetry(
        vendorUserId: Int,
        points: List<TelemetryPoint>,
    ): AppResult<TelemetryDispatchPlan, TelemetryError>

    /** Publishes accepted telemetry to the volatile live-map and history-buffer paths. */
    suspend fun publishTelemetry(plan: TelemetryDispatchPlan)

    /**
     * Resolves the vendor's currently active vehicle, then publishes and enqueues each plausible
     * point (see [GpsPlausibility]) against it — a point that fails the plausibility check is
     * dropped silently, not an error, the same way a tunnel's total signal loss is: the vehicle's
     * last good position just holds until a believable one arrives.
     */
    suspend fun ingestTelemetry(
        vendorUserId: Int,
        points: List<TelemetryPoint>,
    ): AppResult<Unit, TelemetryError> =
        prepareTelemetry(vendorUserId, points).foldSuspend(
            onSuccess = { plan ->
                publishTelemetry(plan)
                AppResult.Success(Unit)
            },
            onError = { error -> AppResult.Error(error) },
        )
}
