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
        assertEquals(100, options.trucks)
        assertEquals(500, options.viewers)
        assertEquals(spec.defaults.viewerConnectRampUpSeconds.seconds, options.viewerConnectRampUp)
        assertEquals(false, options.synchronizeTrucks)
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
                    "--viewer-connect-ramp-up-seconds",
                    "0",
                    "--login-burst",
                    "false",
                ),
                spec,
            )

        assertEquals(WorkloadSpec.Placement.SINGLE_REGION, options.profile.placement)
        assertEquals(45.seconds, options.duration)
        assertNull(options.viewerSession)
        assertEquals(200, options.trucks)
        assertEquals(0.seconds, options.viewerConnectRampUp)
        assertEquals(true, options.synchronizeTrucks)
        assertEquals(false, options.loginBurst)
    }

    @Test
    fun `a second loopback node enables a multi-process run`() {
        val options =
            RunOptions.parse(
                listOf("--node-b-url", "http://127.0.0.1:18081"),
                spec,
            )

        assertEquals("http://127.0.0.1:18081", options.nodeBUrl)
    }

    @Test
    fun `country-wide profile supports a synchronized 200 truck 200 viewer run`() {
        val options =
            RunOptions.parse(
                listOf(
                    "--profile",
                    "country-wide",
                    "--trucks",
                    "200",
                    "--viewers",
                    "200",
                    "--duration-seconds",
                    "60",
                    "--truck-interval-seconds",
                    "1.2",
                ),
                spec,
            )

        assertEquals(WorkloadSpec.Placement.UNIFORM_REGIONS, options.profile.placement)
        assertEquals(200, options.trucks)
        assertEquals(200, options.viewers)
        assertEquals(true, options.synchronizeTrucks)
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
            listOf("--trucks", "0"),
            listOf("--trucks", "${spec.trucks + 1}"),
            listOf("--viewers", "0"),
            listOf("--viewers", "${spec.buyers + 1}"),
            listOf("--viewer-connect-ramp-up-seconds", "-1"),
            listOf("--viewer-connect-ramp-up-seconds", "61"),
            listOf("--synchronize-trucks", "perhaps"),
            listOf("--duration-seconds", "600", "--truck-interval-seconds", "0.02"),
            listOf("--radius-km", "${spec.maxRadiusKm + 1}"),
            listOf("--base-url", "https://staging.example.com"),
            listOf("--node-b-url", "https://staging.example.com"),
            listOf("--node-b-url", "http://127.0.0.1:18080"),
            listOf(
                "--base-url",
                "http://127.0.0.1:18080/api",
                "--node-b-url",
                "http://127.0.0.1:18080",
            ),
            listOf("--base-url", "http://127.0.0.1", "--node-b-url", "http://127.0.0.1:80"),
            listOf("--base-url", "http://localhost:18080", "--node-b-url", "http://127.0.0.1:18080"),
            listOf("--node-b-url", "http://127.0.0.1:18081", "--viewers", "1"),
        ).forEach { args ->
            assertFailsWith<IllegalArgumentException>("args $args") { RunOptions.parse(args, spec) }
        }
    }
}
