/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class RunOptionsTest {
    private val spec = WorkloadSpec.load()

    @Test
    fun `the shipped workload parses and its defaults satisfy the runner's own validation`() {
        val options = RunOptions.parse(emptyList(), spec)

        assertEquals(spec.defaults.durationSeconds.seconds, options.duration)
        assertEquals(spec.profiles.keys.first(), options.profileName)
        assertEquals(true, options.loginBurst)
    }

    @Test
    fun `flags override the defaults and a zero session length disables churn`() {
        val options =
            RunOptions.parse(
                listOf(
                    "--profile",
                    "hotspot",
                    "--duration-seconds",
                    "45",
                    "--viewer-session-seconds",
                    "0",
                    "--login-burst",
                    "false",
                ),
                spec,
            )

        assertEquals(WorkloadSpec.Placement.SINGLE_REGION, options.profile.placement)
        assertEquals(45.seconds, options.duration)
        assertNull(options.viewerSession)
        assertEquals(false, options.loginBurst)
    }

    @Test
    fun `bad input is rejected with an error rather than silently ignored`() {
        listOf(
            listOf("--profile"),
            listOf("--typo", "1"),
            listOf("--profile", "nope"),
            listOf("--duration-seconds", "5"),
            listOf("--duration-seconds", "100000"),
            listOf("--points-per-batch", "0"),
            listOf("--radius-km", "${spec.maxRadiusKm + 1}"),
            listOf("--base-url", "https://staging.example.com"),
        ).forEach { args ->
            assertFailsWith<IllegalArgumentException>("args $args") { RunOptions.parse(args, spec) }
        }
    }
}
