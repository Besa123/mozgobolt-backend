/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

private const val GROWTH_FACTOR = 1.02
private const val BUCKET_COUNT = 1_100
private const val MILLIS_PER_SECOND = 1_000.0
private const val ROUNDING_SCALE = 100.0
private val LN_GROWTH = ln(GROWTH_FACTOR)

/**
 * A constant-memory log-bucketed histogram: every recorded duration lands in a bucket at most
 * 2% wide, so percentiles are accurate to 2% regardless of how many samples a long run records
 * (a list of raw samples would grow with request count). Not thread-safe; confine it to one coroutine.
 */
internal class LatencyHistogram {
    private val counts = LongArray(BUCKET_COUNT)
    var count = 0L
        private set
    private var max = Duration.ZERO

    fun record(latency: Duration) {
        counts[bucketOf(latency)]++
        count++
        if (latency > max) max = latency
    }

    /** The upper bound of the bucket containing the [quantile] (0..1) sample, or zero when empty. */
    fun percentile(quantile: Double): Duration {
        if (count == 0L) return Duration.ZERO
        val rank = ceil(quantile * count).toLong().coerceAtLeast(1)
        val bucket = counts.runningFold(0L, Long::plus).drop(1).indexOfFirst { it >= rank }
        return minOf(upperBoundOf(bucket), max)
    }

    fun summary() =
        LatencySummary(
            count = count,
            p50Ms = percentile(0.50).toMillisRounded(),
            p95Ms = percentile(0.95).toMillisRounded(),
            p99Ms = percentile(0.99).toMillisRounded(),
            maxMs = max.toMillisRounded(),
        )

    private fun bucketOf(latency: Duration): Int {
        val micros = latency.inWholeMicroseconds.coerceAtLeast(1).toDouble()
        return ceil(ln(micros) / LN_GROWTH).toInt().coerceIn(0, BUCKET_COUNT - 1)
    }

    private fun upperBoundOf(bucket: Int): Duration = GROWTH_FACTOR.pow(bucket).microseconds
}

@Serializable
internal data class LatencySummary(
    val count: Long,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val maxMs: Double,
)

private fun Duration.toMillisRounded(): Double =
    Math.round(inWholeMicroseconds / MILLIS_PER_SECOND * ROUNDING_SCALE) / ROUNDING_SCALE
