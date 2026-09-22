package com.mozgobolt.feature.vehicleTracking.routing.dto.request

import com.mozgobolt.core.domain.validation.ValidatedRequest
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoBounds
import kotlinx.serialization.Serializable
import java.time.Instant

private const val MAX_BATCH_SIZE = 100

@Serializable
data class TelemetryPointDto(
    val latitude: Double,
    val longitude: Double,
    val recordedAt: String,
)

@Serializable
data class TelemetryBatchRequestDto(
    val points: List<TelemetryPointDto>,
) : ValidatedRequest {
    override fun validate() =
        buildList {
            if (points.isEmpty()) add("At least one telemetry point is required")
            if (points.size > MAX_BATCH_SIZE) add("A telemetry batch must not exceed $MAX_BATCH_SIZE points")

            points.forEachIndexed { index, point ->
                if (point.latitude !in GeoBounds.MIN_LATITUDE..GeoBounds.MAX_LATITUDE) {
                    add(
                        "Point $index: latitude must be between " +
                            "${GeoBounds.MIN_LATITUDE} and ${GeoBounds.MAX_LATITUDE}",
                    )
                }
                if (point.longitude !in GeoBounds.MIN_LONGITUDE..GeoBounds.MAX_LONGITUDE) {
                    add(
                        "Point $index: longitude must be between " +
                            "${GeoBounds.MIN_LONGITUDE} and ${GeoBounds.MAX_LONGITUDE}",
                    )
                }
                if (runCatching { Instant.parse(point.recordedAt) }.isFailure) {
                    add("Point $index: recordedAt must be a valid ISO-8601 instant")
                }
            }
        }
}
