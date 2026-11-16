/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import kotlinx.serialization.Serializable

@Serializable
internal data class RunDescription(
    val profile: String,
    val durationSeconds: Int,
    val trucks: Int,
    val viewers: Int,
    val radiusKm: Double,
    val truckIntervalSeconds: Double,
    val pointsPerBatch: Int,
    val viewerSessionSeconds: Int,
    val loginBurst: Boolean,
    val seed: Long,
)

@Serializable
internal data class LiveFeedSummary(
    val events: Map<String, Int>,
    val sessionsOpened: Int,
    val outsideRadiusEvents: Int,
    val expectedLiveDeliveries: Long,
    val observedLiveDeliveries: Long,
    val liveDeliveryRatio: Double,
    /** Time from a telemetry request leaving the generator to its position arriving on a viewer's stream. */
    val deliveryLatency: LatencySummary,
)

@Serializable
internal data class LoadTestReport(
    val run: RunDescription,
    val requests: Map<String, LatencySummary>,
    val httpStatuses: Map<String, Int>,
    val liveFeed: LiveFeedSummary,
    val generator: Map<String, LatencySummary>,
    val warnings: List<String>,
    val functionalFailures: Map<String, Int>,
) {
    val passed: Boolean get() = functionalFailures.isEmpty()
}
