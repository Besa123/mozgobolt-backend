/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RegionAllocationTest {
    @Test
    fun `apportion always adds up to exactly the total`() {
        listOf(1, 7, 100, 500, 1_001).forEach { total ->
            listOf(0.0, 0.5, 1.0, 2.5).forEach { skew ->
                assertEquals(total, apportion(total, zipfWeights(20, skew)).sum(), "total=$total skew=$skew")
            }
        }
    }

    @Test
    fun `zero skew splits evenly and positive skew favours the first regions`() {
        assertEquals(List(4) { 25 }, apportion(100, zipfWeights(4, 0.0)))

        val skewed = apportion(500, zipfWeights(20, 1.0))
        assertTrue(
            skewed.zipWithNext().all { (busier, quieter) ->
                busier >= quieter
            },
            "counts must not increase with rank: $skewed",
        )
        assertTrue(skewed.first() > skewed.last() * 10, "the busiest region should dwarf the quietest: $skewed")
    }

    @Test
    fun `assignBuckets groups actors bucket by bucket and is deterministic`() {
        val weights = zipfWeights(3, 1.0)

        val assigned = assignBuckets(11, weights)

        assertEquals(assigned, assignBuckets(11, weights))
        assertEquals(assigned.sorted(), assigned, "actors are grouped by bucket in order")
        assertEquals(apportion(11, weights), List(3) { bucket -> assigned.count { it == bucket } })
    }

    @Test
    fun `round-robin selection spreads a bounded actor set across regions`() {
        val actors = listOf(0 to 0, 1 to 0, 2 to 0, 3 to 1, 4 to 1, 5 to 2, 6 to 2, 7 to 2)

        val selected = takeRoundRobinByBucket(actors, 6) { it.second }

        assertEquals(listOf(0, 3, 5, 1, 4, 6), selected.map { it.first })
        assertEquals(
            listOf(2, 2, 2),
            selected
                .groupingBy { it.second }
                .eachCount()
                .values
                .sorted(),
        )
    }

    @Test
    fun `round-robin selection rejects a request beyond the available fixture`() {
        assertFailsWith<IllegalArgumentException> {
            takeRoundRobinByBucket(listOf(0), 2) { it }
        }
    }
}
