/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

// Initial map connections are spread over this window, like users opening the app over a few seconds.
private val CONNECT_RAMP_UP = 5.seconds

// Time for every initial snapshot to arrive before trucks start moving.
private val SNAPSHOT_SETTLE = 3.seconds

// Time for the last telemetry batch to fan out to every viewer before final maps are compared.
private val FINAL_DRAIN = 3.seconds

private val SCHEDULER_PROBE_PERIOD = 100.milliseconds
private val AUTH_BURSTS_START_AFTER = 10.seconds
private val TOKEN_VALIDITY_SETUP_ALLOWANCE = 60.seconds

private const val HTTP_OK = 200
private const val HTTP_ACCEPTED = 202
private const val RUN_ID_LENGTH = 8

private val PUBLICATION_PROBE_TIMEOUT = 3.seconds
private const val PUBLICATION_PROBE_RADIUS_KM = 1.0

private val REPORT_JSON = Json { prettyPrint = true }

/**
 * One end-to-end run against a real local app: a fleet uploading telemetry, a crowd of buyers
 * opening and closing live maps, and login bursts, all at once, observed only through HTTP.
 */
internal class LiveWorkload(
    private val spec: WorkloadSpec,
    private val options: RunOptions,
    private val accounts: AccountBook,
) {
    suspend fun run(): LoadTestReport =
        coroutineScope {
            val observations = Channel<Observation>(Channel.UNLIMITED)
            val ledger =
                async(Dispatchers.Default) {
                    observations.consumeAsFlow().fold(RunLedger()) { ledger, observation ->
                        ledger.apply { record(observation) }
                    }
                }
            try {
                newLoadHttpClient().use { http ->
                    val sink = ObservationSink { observations.trySend(it) }
                    scenario(LiveApi(http, options.baseUrl, sink), sink)
                }
            } finally {
                observations.close()
            }
            ledger.await().report(description())
        }

    private suspend fun scenario(
        api: LiveApi,
        sink: ObservationSink,
    ) = coroutineScope {
        check(api.health() == HTTP_OK) { "The app at ${options.baseUrl} is not healthy" }
        val generatorHealth = launch { fixedRate(SCHEDULER_PROBE_PERIOD).collect { sink.recordSchedulerLag(it) } }

        val fleet = fleet(api, sink).also { requireAllAccepted(it.prime()) }
        requireInitialPositionsPublished(api, fleet)
        val crowd = crowd(api, sink)
        val crowdJob = launch { crowd.run(CONNECT_RAMP_UP) }
        delay(CONNECT_RAMP_UP + SNAPSHOT_SETTLE)

        driveFleetWhileAuthBurstsFire(fleet, api, sink)

        delay(FINAL_DRAIN)
        crowd.checkFinalMaps()
        crowdJob.cancelAndJoin()
        generatorHealth.cancelAndJoin()
    }

    private suspend fun driveFleetWhileAuthBurstsFire(
        fleet: Fleet,
        api: LiveApi,
        sink: ObservationSink,
    ) = coroutineScope {
        if (options.loginBurst) {
            launch {
                delay(AUTH_BURSTS_START_AFTER.coerceAtMost(options.duration / 3))
                AuthBursts(api, sink, accounts.buyers.take(spec.loginBurst), spec.authLimitPerMinute).run()
            }
        }
        val driving = launch { fleet.run(startedAt = Instant.now()) }
        delay(options.duration)
        driving.cancelAndJoin()
    }

    private suspend fun requireInitialPositionsPublished(
        api: LiveApi,
        fleet: Fleet,
    ) {
        // HTTP 202 only says a batch was accepted: the app silently drops a point that implies an impossible
        // speed from the vehicle's last known position, and remembers that position for five minutes. A run
        // starting within that window of one with another profile would report confusing map failures, so
        // every truck's first position is checked against a tiny live map around it.
        val unpublished =
            coroutineScope {
                fleet.trucks
                    .zip(accounts.buyers)
                    .map { (truck, buyer) -> async { !api.isPublishedOnLiveMap(truck, buyer) } }
                    .awaitAll()
                    .count { it }
            }
        check(unpublished == 0) {
            "$unpublished of ${fleet.trucks.size} trucks' initial positions were accepted but not published. The app " +
                "keeps each vehicle's last position for 5 minutes and drops a jump from it as implausible (typical " +
                "after a run with another profile): restart the app or wait 5 minutes."
        }
    }

    private fun requireAllAccepted(statuses: List<Int>) =
        check(statuses.all { it == HTTP_ACCEPTED }) {
            "Initial telemetry was not accepted: HTTP statuses ${statuses.groupingBy { it }.eachCount()}"
        }

    private fun fleet(
        api: LiveApi,
        sink: ObservationSink,
    ) = Fleet(
        trucks = accounts.vendors.map { Truck.forVendor(it, truckRegion(it), spec.routeRadiusKm, options.seed) },
        api = api,
        sink = sink,
        schedule = TelemetrySchedule(options.truckInterval, options.pointsPerBatch),
        runId = UUID.randomUUID().toString().take(RUN_ID_LENGTH),
    )

    private fun crowd(
        api: LiveApi,
        sink: ObservationSink,
    ) = ViewerCrowd(
        viewers = accounts.buyers.take(options.profile.viewers).map { Viewer(it, viewerHome(it)) },
        api = api,
        sink = sink,
        radiusKm = options.radiusKm,
        meanSession = options.viewerSession,
        seed = options.seed,
    )

    private fun truckRegion(vendor: VendorAccount): WorkloadSpec.Region =
        when (options.profile.placement) {
            WorkloadSpec.Placement.SEEDED -> spec.regions[vendor.regionIndex]
            WorkloadSpec.Placement.SINGLE_REGION -> spec.regions.first()
        }

    private fun viewerHome(buyer: BuyerAccount): GeoPoint =
        when (options.profile.placement) {
            WorkloadSpec.Placement.SEEDED -> {
                buyer.home
            }

            WorkloadSpec.Placement.SINGLE_REGION -> {
                val random = Random(options.seed xor buyer.credentials.userId.toLong())
                spec.regions
                    .first()
                    .center
                    .scatteredBy(spec.cityRadiusKm, random)
                    .roundedToWirePrecision()
            }
        }

    private fun description() =
        RunDescription(
            profile = options.profileName,
            durationSeconds = options.duration.inWholeSeconds.toInt(),
            trucks = accounts.vendors.size,
            viewers = options.profile.viewers,
            radiusKm = options.radiusKm,
            truckIntervalSeconds = options.truckInterval.toDouble(DurationUnit.SECONDS),
            pointsPerBatch = options.pointsPerBatch,
            viewerSessionSeconds = options.viewerSession?.inWholeSeconds?.toInt() ?: 0,
            loginBurst = options.loginBurst,
            seed = options.seed,
        )
}

fun main(args: Array<String>) {
    val spec = WorkloadSpec.load()
    val options = RunOptions.parse(args.toList(), spec)
    val accounts = AccountBook.load(options.accountsPath)
    accounts.requireTokensValidFor(options.duration + TOKEN_VALIDITY_SETUP_ALLOWANCE)

    val report = runBlocking { LiveWorkload(spec, options, accounts).run() }

    Files.createDirectories(options.reportPath.parent)
    val json = REPORT_JSON.encodeToString(report)
    Files.writeString(options.reportPath, json + "\n")
    println(json)
    println("\nReport written to ${options.reportPath}")
    exitProcess(if (report.passed) 0 else 1)
}

private fun ObservationSink.recordSchedulerLag(tick: Tick) {
    record(Observation.GeneratorLag(LagSource.SCHEDULER, tick.lateness))
}

private suspend fun LiveApi.isPublishedOnLiveMap(
    truck: Truck,
    buyer: BuyerAccount,
): Boolean =
    withTimeoutOrNull(PUBLICATION_PROBE_TIMEOUT) {
        liveFeed(buyer, truck.positionAt(Duration.ZERO).roundedToWirePrecision(), PUBLICATION_PROBE_RADIUS_KM)
            .firstOrNull { it is LiveFeedEvent.Location && it.fix.vehicleId == truck.vendor.vehicleId }
    } != null
