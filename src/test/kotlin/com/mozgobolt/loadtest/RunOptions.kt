/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import java.net.URI
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
private val MIN_DURATION = 30.seconds
private val MAX_DURATION = 10.minutes

private const val DEFAULT_BASE_URL = "http://127.0.0.1:18080"
private const val DEFAULT_REPORT = "build/load-test/live-workload-report.json"
private const val DEFAULT_SEED = 20_260_516L

private val KNOWN_FLAGS =
    setOf(
        "profile",
        "base-url",
        "accounts",
        "report",
        "duration-seconds",
        "truck-interval-seconds",
        "points-per-batch",
        "radius-km",
        "viewer-session-seconds",
        "login-burst",
        "seed",
    )

/** Everything a run can be tuned with from the command line; defaults come from `workload.json`. */
internal data class RunOptions(
    val profileName: String,
    val profile: WorkloadSpec.Profile,
    val baseUrl: String,
    val accountsPath: Path,
    val reportPath: Path,
    val duration: Duration,
    val truckInterval: Duration,
    val pointsPerBatch: Int,
    val radiusKm: Double,
    /** Mean time a buyer keeps a map open; `null` means maps stay open for the whole run. */
    val viewerSession: Duration?,
    /** Whether to fire the login bursts that compete with telemetry and live maps for CPU. */
    val loginBurst: Boolean,
    val seed: Long,
) {
    private fun validate(spec: WorkloadSpec) {
        require(URI.create(baseUrl).host in LOOPBACK_HOSTS) {
            "This runner only targets loopback: its synthetic X-Forwarded-For addresses are only valid " +
                "for a disposable local stack"
        }
        require(duration in MIN_DURATION..MAX_DURATION) {
            "--duration-seconds must be between ${MIN_DURATION.inWholeSeconds} and ${MAX_DURATION.inWholeSeconds}; " +
                "seeded access tokens only live 15 minutes"
        }
        require(truckInterval > Duration.ZERO) { "--truck-interval-seconds must be positive" }
        require(pointsPerBatch in 1..spec.maxPointsPerBatch) {
            "--points-per-batch must be between 1 and ${spec.maxPointsPerBatch}"
        }
        require(radiusKm > 0 && radiusKm <= spec.maxRadiusKm) {
            "--radius-km must be greater than 0 and at most ${spec.maxRadiusKm}"
        }
    }

    companion object {
        fun parse(
            args: List<String>,
            spec: WorkloadSpec,
        ): RunOptions {
            require(args.size % 2 == 0) { "Options are --name value pairs, got: $args" }
            val flags = args.chunked(2).associate { (name, value) -> name.removePrefix("--") to value }
            val unknown = flags.keys - KNOWN_FLAGS
            require(unknown.isEmpty()) { "Unknown options $unknown; supported: $KNOWN_FLAGS" }

            val profileName = flags["profile"] ?: spec.profiles.keys.first()
            val sessionSeconds = flags["viewer-session-seconds"]?.toInt() ?: spec.defaults.viewerSessionSeconds
            val trucksEverySeconds = flags["truck-interval-seconds"]?.toDouble() ?: spec.defaults.truckIntervalSeconds
            val profile = spec.profiles[profileName]
            return RunOptions(
                profileName = profileName,
                profile =
                    requireNotNull(
                        spec.profiles[profileName],
                    ) { "Unknown profile $profileName; use ${spec.profiles.keys}" },
                baseUrl = (flags["base-url"] ?: DEFAULT_BASE_URL).trimEnd('/'),
                accountsPath = Path.of(flags["accounts"] ?: AccountBook.DEFAULT_PATH),
                reportPath = Path.of(flags["report"] ?: DEFAULT_REPORT),
                duration = (flags["duration-seconds"]?.toInt() ?: spec.defaults.durationSeconds).seconds,
                truckInterval = trucksEverySeconds.seconds,
                pointsPerBatch = flags["points-per-batch"]?.toInt() ?: spec.defaults.pointsPerBatch,
                radiusKm = flags["radius-km"]?.toDouble() ?: spec.defaults.radiusKm,
                viewerSession = sessionSeconds.takeIf { it > 0 }?.seconds,
                loginBurst = flags["login-burst"]?.toBooleanStrict() ?: true,
                seed = flags["seed"]?.toLong() ?: DEFAULT_SEED,
            ).apply { validate(spec) }
        }
    }
}
