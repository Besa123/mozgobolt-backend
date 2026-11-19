/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import java.net.URI
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
private val MIN_DURATION = 30.seconds
private val MAX_DURATION = 10.minutes
private val MAX_VIEWER_CONNECT_RAMP_UP = 60.seconds

private const val DEFAULT_BASE_URL = "http://127.0.0.1:18080"
private const val DEFAULT_REPORT = "build/load-test/live-workload-report.json"
private const val DEFAULT_SEED = 20_260_516L
private const val MAX_TELEMETRY_POINTS_PER_RUN = 1_000_000L
private const val MAX_POINT_VIEWER_PAIRS_PER_RUN = 4_000_000L
private val MIN_SAMPLE_SPACING = 2.milliseconds

private fun URI.sameOriginAs(other: URI): Boolean =
    scheme.equals(other.scheme, ignoreCase = true) &&
        normalizedLoopbackHost() == other.normalizedLoopbackHost() &&
        effectivePort() == other.effectivePort()

private fun URI.normalizedLoopbackHost() =
    when (host?.lowercase()) {
        "localhost", "127.0.0.1" -> "ipv4-loopback"
        "::1" -> "ipv6-loopback"
        else -> host
    }

private fun URI.effectivePort(): Int =
    port.takeIf { it >= 0 }
        ?: when {
            scheme.equals("http", ignoreCase = true) -> 80
            scheme.equals("https", ignoreCase = true) -> 443
            else -> -1
        }

private val KNOWN_FLAGS =
    setOf(
        "profile",
        "base-url",
        "node-b-url",
        "accounts",
        "report",
        "trucks",
        "viewers",
        "duration-seconds",
        "truck-interval-seconds",
        "points-per-batch",
        "radius-km",
        "viewer-session-seconds",
        "viewer-connect-ramp-up-seconds",
        "synchronize-trucks",
        "login-burst",
        "seed",
    )

private fun parseFlags(args: List<String>): Map<String, String> {
    require(args.size % 2 == 0) { "Options are --name value pairs, got: $args" }
    val flags = args.chunked(2).associate { (name, value) -> name.removePrefix("--") to value }
    val unknown = flags.keys - KNOWN_FLAGS
    require(unknown.isEmpty()) { "Unknown options $unknown; supported: $KNOWN_FLAGS" }
    return flags
}

private fun Map<String, String>.intValue(
    name: String,
    default: Int,
): Int = this[name]?.toInt() ?: default

private fun Map<String, String>.doubleValue(
    name: String,
    default: Double,
): Double = this[name]?.toDouble() ?: default

private fun Map<String, String>.booleanValue(
    name: String,
    default: Boolean,
): Boolean = this[name]?.toBooleanStrict() ?: default

/** Everything a run can be tuned with from the command line; defaults come from `workload.json`. */
internal data class RunOptions(
    val profileName: String,
    val profile: WorkloadSpec.Profile,
    val baseUrl: String,
    val nodeBUrl: String?,
    val accountsPath: Path,
    val reportPath: Path,
    val trucks: Int,
    val viewers: Int,
    val duration: Duration,
    val truckInterval: Duration,
    val pointsPerBatch: Int,
    val radiusKm: Double,
    /** Mean time a buyer keeps a map open; `null` means maps stay open for the whole run. */
    val viewerSession: Duration?,
    val viewerConnectRampUp: Duration,
    val synchronizeTrucks: Boolean,
    /** Whether to fire the login bursts that compete with telemetry and live maps for CPU. */
    val loginBurst: Boolean,
    val seed: Long,
) {
    private fun validate(spec: WorkloadSpec) {
        val baseUri = URI.create(baseUrl)
        require(baseUri.host in LOOPBACK_HOSTS) {
            "This runner only targets loopback: its synthetic X-Forwarded-For addresses are only valid " +
                "for a disposable local stack"
        }
        nodeBUrl?.let { url ->
            val nodeBUri = URI.create(url)
            require(nodeBUri.host in LOOPBACK_HOSTS) {
                "--node-b-url must target loopback because synthetic X-Forwarded-For addresses are only valid " +
                    "for a disposable local stack"
            }
            require(!nodeBUri.sameOriginAs(baseUri)) {
                "--node-b-url must identify a different app origin than --base-url"
            }
            require(viewers >= 2) { "--viewers must be at least 2 when --node-b-url is set" }
        }
        require(duration in MIN_DURATION..MAX_DURATION) {
            "--duration-seconds must be between ${MIN_DURATION.inWholeSeconds} and ${MAX_DURATION.inWholeSeconds}; " +
                "seeded access tokens only live 15 minutes"
        }
        require(trucks in 1..spec.trucks) {
            "--trucks must be between 1 and the ${spec.trucks} seeded vehicles"
        }
        require(viewers in 1..spec.buyers) {
            "--viewers must be between 1 and the ${spec.buyers} seeded buyers"
        }
        require(trucks <= spec.buyers) {
            "The publication preflight needs one unique buyer per truck; seed at least $trucks buyers"
        }
        require(truckInterval.isFinite() && truckInterval > Duration.ZERO) {
            "--truck-interval-seconds must be finite and positive"
        }
        require(pointsPerBatch in 1..spec.maxPointsPerBatch) {
            "--points-per-batch must be between 1 and ${spec.maxPointsPerBatch}"
        }
        require(truckInterval / pointsPerBatch >= MIN_SAMPLE_SPACING) {
            "Telemetry samples must be at least $MIN_SAMPLE_SPACING apart"
        }
        require(radiusKm > 0 && radiusKm <= spec.maxRadiusKm) {
            "--radius-km must be greater than 0 and at most ${spec.maxRadiusKm}"
        }
        require(viewerConnectRampUp >= Duration.ZERO && viewerConnectRampUp <= MAX_VIEWER_CONNECT_RAMP_UP) {
            "--viewer-connect-ramp-up-seconds must be between 0 and ${MAX_VIEWER_CONNECT_RAMP_UP.inWholeSeconds}"
        }

        val durationNanos = duration.inWholeNanoseconds
        val intervalNanos = truckInterval.inWholeNanoseconds
        require(intervalNanos > 0) { "--truck-interval-seconds is below the supported clock resolution" }
        val estimatedBatches = durationNanos / intervalNanos + 1
        val estimatedPoints = trucks.toLong() * estimatedBatches * pointsPerBatch
        require(estimatedPoints <= MAX_TELEMETRY_POINTS_PER_RUN) {
            "This run would generate up to $estimatedPoints telemetry points; limit is $MAX_TELEMETRY_POINTS_PER_RUN"
        }
        val maximumDeliveryComparisons = estimatedPoints * viewers
        require(maximumDeliveryComparisons <= MAX_POINT_VIEWER_PAIRS_PER_RUN) {
            "This run would compare up to $maximumDeliveryComparisons point/viewer pairs; " +
                "limit is $MAX_POINT_VIEWER_PAIRS_PER_RUN"
        }
    }

    fun validateAccounts(accounts: AccountBook) {
        require(trucks <= accounts.vendors.size) {
            "--trucks requests $trucks but the accounts file contains only ${accounts.vendors.size} vendors"
        }
        require(trucks <= accounts.buyers.size) {
            "The publication preflight needs one unique buyer per truck; the accounts file has only " +
                "${accounts.buyers.size}"
        }
        require(viewers <= accounts.buyers.size) {
            "--viewers requests $viewers but the accounts file contains only ${accounts.buyers.size} buyers"
        }
    }

    companion object {
        fun parse(
            args: List<String>,
            spec: WorkloadSpec,
        ): RunOptions {
            val flags = parseFlags(args)
            val profileName = flags["profile"] ?: spec.profiles.keys.first()
            val profile =
                requireNotNull(spec.profiles[profileName]) {
                    "Unknown profile $profileName; use ${spec.profiles.keys}"
                }
            val sessionSeconds = flags.intValue("viewer-session-seconds", spec.defaults.viewerSessionSeconds)
            require(sessionSeconds >= 0) { "--viewer-session-seconds must not be negative" }
            val connectRampUpSeconds =
                flags.intValue("viewer-connect-ramp-up-seconds", spec.defaults.viewerConnectRampUpSeconds)
            return RunOptions(
                profileName = profileName,
                profile = profile,
                baseUrl = (flags["base-url"] ?: DEFAULT_BASE_URL).trimEnd('/'),
                nodeBUrl = flags["node-b-url"]?.trimEnd('/'),
                accountsPath = Path.of(flags["accounts"] ?: AccountBook.DEFAULT_PATH),
                reportPath = Path.of(flags["report"] ?: DEFAULT_REPORT),
                trucks = flags.intValue("trucks", profile.trucks),
                viewers = flags.intValue("viewers", profile.viewers),
                duration = flags.intValue("duration-seconds", spec.defaults.durationSeconds).seconds,
                truckInterval = flags.doubleValue("truck-interval-seconds", spec.defaults.truckIntervalSeconds).seconds,
                pointsPerBatch = flags.intValue("points-per-batch", spec.defaults.pointsPerBatch),
                radiusKm = flags.doubleValue("radius-km", spec.defaults.radiusKm),
                viewerSession = sessionSeconds.takeIf { it > 0 }?.seconds,
                viewerConnectRampUp = connectRampUpSeconds.seconds,
                synchronizeTrucks = flags.booleanValue("synchronize-trucks", profile.synchronizeTrucks),
                loginBurst = flags.booleanValue("login-burst", true),
                seed = flags["seed"]?.toLong() ?: DEFAULT_SEED,
            ).apply { validate(spec) }
        }
    }
}
