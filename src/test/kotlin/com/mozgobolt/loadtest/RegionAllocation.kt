/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import kotlin.math.floor
import kotlin.math.pow

/** Zipf weights `1 / rank^exponent` for ranks 1..[count]; exponent 0 gives equal weights. */
internal fun zipfWeights(
    count: Int,
    exponent: Double,
): List<Double> = List(count) { index -> (index + 1.0).pow(-exponent) }

/**
 * Splits [total] actors across buckets proportionally to [weights] with the largest-remainder
 * (Hamilton) method, so the counts always add up to exactly [total] and the result is deterministic.
 */
internal fun apportion(
    total: Int,
    weights: List<Double>,
): List<Int> {
    val weightSum = weights.sum()
    val quotas = weights.map { total * it / weightSum }
    val floors = quotas.map { floor(it).toInt() }
    val leftover = total - floors.sum()
    val bucketsByRemainder =
        quotas.indices
            .sortedByDescending { quotas[it] - floors[it] }
            .take(leftover)
            .toSet()
    return floors.mapIndexed { index, count -> if (index in bucketsByRemainder) count + 1 else count }
}

/** The bucket index of each of [total] actors, grouped bucket by bucket (actor 0 is in bucket 0 first). */
internal fun assignBuckets(
    total: Int,
    weights: List<Double>,
): List<Int> = apportion(total, weights).flatMapIndexed { bucket, count -> List(count) { bucket } }

/** Selects actors round-robin by ascending bucket index without changing their stored locations. */
internal fun <T> takeRoundRobinByBucket(
    actors: List<T>,
    count: Int,
    bucketOf: (T) -> Int,
): List<T> {
    require(count in 0..actors.size) { "count must be between 0 and ${actors.size}" }
    if (count == 0) return emptyList()

    val buckets =
        actors
            .groupBy(bucketOf)
            .toSortedMap()
            .values
            .map { it.iterator() }
    val selected = ArrayList<T>(count)
    while (selected.size < count) {
        check(appendOneFromEachBucket(buckets, selected, count)) {
            "Unable to select $count actors from ${actors.size} available actors"
        }
    }
    return selected
}

private fun <T> appendOneFromEachBucket(
    buckets: List<Iterator<T>>,
    selected: MutableList<T>,
    limit: Int,
): Boolean {
    var appended = false
    for (bucket in buckets) {
        if (selected.size == limit) break
        if (bucket.hasNext()) {
            selected += bucket.next()
            appended = true
        }
    }
    return appended
}
