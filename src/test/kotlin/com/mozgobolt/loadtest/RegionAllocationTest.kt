/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
