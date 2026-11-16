/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryPoint
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

private const val MIN_SPEED_KMH = 15.0
private const val MAX_SPEED_KMH = 45.0

/** A vendor's vehicle driving one repeating loop at a constant urban speed. */
internal class Truck(
    val vendor: VendorAccount,
    private val route: TruckRoute,
    private val speedKmh: Double,
    private val phaseKm: Double,
) {
    /** Where the truck is [elapsed] after the run's start: a pure function of time, with no carried state. */
    fun positionAt(elapsed: Duration): GeoPoint =
        route.positionAt(phaseKm + speedKmh * elapsed.toDouble(DurationUnit.HOURS))

    companion object {
        fun forVendor(
            vendor: VendorAccount,
            region: WorkloadSpec.Region,
            routeRadiusKm: Double,
            seed: Long,
        ): Truck {
            val random = Random(seed xor vendor.vehicleId.toLong())
            val route = TruckRoute.loopAround(region.center, routeRadiusKm, random)
            return Truck(
                vendor = vendor,
                route = route,
                speedKmh = random.nextDouble(MIN_SPEED_KMH, MAX_SPEED_KMH),
                phaseKm = random.nextDouble(route.lengthKm),
            )
        }
    }
}

/** A device uploads one batch every [interval], each holding [pointsPerBatch] evenly spaced GPS samples. */
internal data class TelemetrySchedule(
    val interval: Duration,
    val pointsPerBatch: Int,
) {
    init {
        require(pointsPerBatch >= 1) { "pointsPerBatch must be positive" }
        require(interval / pointsPerBatch >= MIN_SAMPLE_SPACING) {
            "Samples must be at least $MIN_SAMPLE_SPACING apart so millisecond timestamps stay strictly increasing"
        }
    }

    private companion object {
        val MIN_SAMPLE_SPACING = Duration.parse("2ms")
    }
}

private data class TelemetryBatch(
    val sequence: Int,
    val points: List<TelemetryPoint>,
    val lateness: Duration,
)

/** The simulated fleet: every truck uploads on its own fixed-rate schedule, staggered across one interval. */
internal class Fleet(
    val trucks: List<Truck>,
    private val api: LiveApi,
    private val sink: ObservationSink,
    private val schedule: TelemetrySchedule,
    private val runId: String,
) {
    /**
     * Every truck reports its starting position once, concurrently, so live maps have vehicles to show.
     * Returns the HTTP statuses.
     */
    suspend fun prime(): List<Int> =
        coroutineScope {
            trucks
                .map { truck ->
                    async {
                        val start =
                            telemetryPoint(
                                truck.positionAt(Duration.ZERO),
                                Instant.now().truncatedTo(ChronoUnit.MILLIS),
                            )
                        truck.upload(sequence = 0, points = listOf(start))
                    }
                }.awaitAll()
        }

    /** Drives every truck until the calling scope is cancelled; [startedAt] anchors all recorded timestamps. */
    suspend fun run(startedAt: Instant) =
        coroutineScope {
            trucks.forEachIndexed { index, truck ->
                val startOffset = schedule.interval * index / trucks.size
                launch {
                    truck.batches(startOffset, startedAt).collect { batch ->
                        sink.record(Observation.GeneratorLag(LagSource.TRUCK_TICK, batch.lateness))
                        truck.upload(batch.sequence, batch.points)
                    }
                }
            }
        }

    private fun Truck.batches(
        startOffset: Duration,
        startedAt: Instant,
    ): Flow<TelemetryBatch> =
        fixedRate(period = schedule.interval, firstDue = startOffset + schedule.interval).map { tick ->
            val batchEnd = startOffset + schedule.interval * (tick.index + 1)
            val sampleSpacing = schedule.interval / schedule.pointsPerBatch
            TelemetryBatch(
                sequence = tick.index + 1,
                lateness = tick.lateness,
                points =
                    List(schedule.pointsPerBatch) { sample ->
                        val offset = batchEnd - schedule.interval + sampleSpacing * (sample + 1)
                        telemetryPoint(
                            positionAt(offset),
                            startedAt.plus(offset.toJavaDuration()).truncatedTo(ChronoUnit.MILLIS),
                        )
                    },
            )
        }

    private suspend fun Truck.upload(
        sequence: Int,
        points: List<TelemetryPoint>,
    ): Int {
        sink.record(Observation.TelemetrySent(vendor.vehicleId, points, TimeSource.Monotonic.markNow()))
        val status =
            api.postTelemetry(
                vendor,
                idempotencyKey = "lt-$runId-${vendor.vehicleId}-$sequence",
                points = points,
            )
        sink.record(Observation.TelemetryAcknowledged(vendor.vehicleId, status))
        return status
    }
}

private fun telemetryPoint(
    position: GeoPoint,
    recordedAt: Instant,
) = TelemetryPoint(position.latitude, position.longitude, recordedAt)
