/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import com.mozgobolt.feature.vehicleTracking.service.H3CellIndexer
import io.ktor.client.HttpClient
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

// Time for every initial snapshot to arrive before trucks start moving.
private val SNAPSHOT_SETTLE = 3.seconds

// Let synchronized bursts and backpressured SSE writers settle before the final convergence check.
private val FINAL_DRAIN = 10.seconds

private val SCHEDULER_PROBE_PERIOD = 100.milliseconds
private val AVAILABILITY_PROBE_PERIOD = 5.seconds
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
    private val cellCenterRegion by lazy { singleCellRegion(spec) }

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
                    scenario(createNodeApis(http, sink), sink)
                }
            } finally {
                observations.close()
            }
            val runLedger = ledger.await()
            runLedger.verifyAllTelemetryAcknowledged()
            if (options.viewerSession == null) {
                runLedger.requirePeakConcurrentConnections(viewersPerNode())
            }
            runLedger.report(description())
        }

    private fun createNodeApis(
        http: HttpClient,
        sink: ObservationSink,
    ) = buildList {
        add(LiveApi(http, options.baseUrl, sink, NODE_A))
        options.nodeBUrl?.let { add(LiveApi(http, it, sink, NODE_B)) }
    }

    private suspend fun scenario(
        nodeApis: List<LiveApi>,
        sink: ObservationSink,
    ) = coroutineScope {
        nodeApis.forEach { api ->
            check(api.health() == HTTP_OK) { "${api.nodeName} is not healthy" }
            check(api.readiness() == HTTP_OK) { "${api.nodeName} database is not ready" }
        }
        val generatorHealth = launch { fixedRate(SCHEDULER_PROBE_PERIOD).collect { sink.recordSchedulerLag(it) } }

        val api = nodeApis.first()
        val fleet = fleet(api, sink).also { requireAllAccepted(it.prime()) }
        requireInitialPositionsPublished(api, fleet)
        nodeApis.drop(1).forEach { relayNode ->
            check(relayNode.isPublishedOnLiveMap(fleet.trucks.first(), accounts.buyers.first())) {
                "${relayNode.nodeName} did not receive a location published through $NODE_A"
            }
        }
        val crowd = crowd(nodeApis, sink)
        val crowdJob = launch { crowd.run(options.viewerConnectRampUp) }
        delay(options.viewerConnectRampUp + SNAPSHOT_SETTLE)

        driveFleetWhileAuthBurstsFire(fleet, nodeApis, sink)

        delay(FINAL_DRAIN)
        crowd.checkFinalMaps()
        crowdJob.cancelAndJoin()
        generatorHealth.cancelAndJoin()
    }

    private suspend fun driveFleetWhileAuthBurstsFire(
        fleet: Fleet,
        nodeApis: List<LiveApi>,
        sink: ObservationSink,
    ) = coroutineScope {
        val availabilityProbes =
            launch {
                fixedRate(AVAILABILITY_PROBE_PERIOD).collect {
                    nodeApis.forEach { api ->
                        val healthStatus = api.health()
                        if (healthStatus != HTTP_OK) {
                            sink.record(
                                Observation.FunctionalFailure(
                                    "${api.nodeName} liveness probe returned HTTP $healthStatus",
                                ),
                            )
                        }
                        val readyStatus = api.readiness()
                        if (readyStatus != HTTP_OK) {
                            sink.record(
                                Observation.FunctionalFailure(
                                    "${api.nodeName} readiness probe returned HTTP $readyStatus",
                                ),
                            )
                        }
                    }
                }
            }
        try {
            if (options.loginBurst) {
                launch {
                    delay(AUTH_BURSTS_START_AFTER.coerceAtMost(options.duration / 3))
                    AuthBursts(
                        nodeApis.first(),
                        sink,
                        accounts.buyers.take(spec.loginBurst),
                        spec.authLimitPerMinute,
                    ).run()
                }
            }
            val driving = launch { fleet.run(startedAt = Instant.now(), runDuration = options.duration) }
            driving.join()
        } finally {
            availabilityProbes.cancelAndJoin()
        }
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
        trucks =
            selectedVendors().map {
                Truck.forVendor(
                    it,
                    truckRegion(it),
                    options.profile.routeRadiusKm ?: spec.routeRadiusKm,
                    options.seed,
                )
            },
        api = api,
        sink = sink,
        schedule = TelemetrySchedule(options.truckInterval, options.pointsPerBatch, options.synchronizeTrucks),
        runId = UUID.randomUUID().toString().take(RUN_ID_LENGTH),
    )

    private fun crowd(
        nodeApis: List<LiveApi>,
        sink: ObservationSink,
    ) = ViewerCrowd(
        viewers = selectedBuyers().map { Viewer(it, viewerHome(it)) },
        apis = nodeApis,
        sink = sink,
        radiusKm = options.radiusKm,
        meanSession = options.viewerSession,
        seed = options.seed,
    )

    private fun selectedVendors() = takeRoundRobinByBucket(accounts.vendors, options.trucks, VendorAccount::regionIndex)

    private fun selectedBuyers() =
        when (options.profile.placement) {
            WorkloadSpec.Placement.UNIFORM_REGIONS -> {
                takeRoundRobinByBucket(accounts.buyers, options.viewers, BuyerAccount::regionIndex)
            }

            WorkloadSpec.Placement.SEEDED,
            WorkloadSpec.Placement.SINGLE_REGION,
            WorkloadSpec.Placement.SINGLE_CELL,
            -> {
                accounts.buyers.take(options.viewers)
            }
        }

    private fun truckRegion(vendor: VendorAccount): WorkloadSpec.Region =
        when (options.profile.placement) {
            WorkloadSpec.Placement.SEEDED,
            WorkloadSpec.Placement.UNIFORM_REGIONS,
            -> {
                spec.regions[vendor.regionIndex]
            }

            WorkloadSpec.Placement.SINGLE_REGION -> {
                spec.regions.first()
            }

            WorkloadSpec.Placement.SINGLE_CELL -> {
                cellCenterRegion
            }
        }

    private fun viewerHome(buyer: BuyerAccount): GeoPoint =
        when (options.profile.placement) {
            WorkloadSpec.Placement.SEEDED,
            WorkloadSpec.Placement.UNIFORM_REGIONS,
            -> {
                buyer.home
            }

            WorkloadSpec.Placement.SINGLE_REGION -> {
                val random = Random(options.seed xor buyer.credentials.userId.toLong())
                spec.regions
                    .first()
                    .center
                    .scatteredBy(options.profile.cityRadiusKm ?: spec.cityRadiusKm, random)
                    .roundedToWirePrecision()
            }

            WorkloadSpec.Placement.SINGLE_CELL -> {
                cellCenterRegion.center
            }
        }

    private fun description() =
        RunDescription(
            profile = options.profileName,
            durationSeconds = options.duration.inWholeSeconds.toInt(),
            trucks = options.trucks,
            viewers = options.viewers,
            appNodes = nodeNames().size,
            viewersPerNode = viewersPerNode(),
            radiusKm = options.radiusKm,
            truckIntervalSeconds = options.truckInterval.toDouble(DurationUnit.SECONDS),
            pointsPerBatch = options.pointsPerBatch,
            viewerSessionSeconds = options.viewerSession?.inWholeSeconds?.toInt() ?: 0,
            viewerConnectRampUpSeconds = options.viewerConnectRampUp.inWholeSeconds.toInt(),
            loginBurst = options.loginBurst,
            synchronizeTrucks = options.synchronizeTrucks,
            seed = options.seed,
        )

    private fun viewersPerNode(): Map<String, Int> =
        nodeNames().let { nodes ->
            nodes
                .mapIndexed { index, nodeName ->
                    nodeName to
                        options.viewers / nodes.size + if (index < options.viewers % nodes.size) 1 else 0
                }.toMap()
        }

    private fun nodeNames(): List<String> =
        buildList {
            add(NODE_A)
            if (options.nodeBUrl != null) add(NODE_B)
        }

    private companion object {
        const val NODE_A = "node-a"
        const val NODE_B = "node-b"
    }
}

internal fun singleCellRegion(spec: WorkloadSpec): WorkloadSpec.Region {
    val anchor = spec.regions.first()
    val indexer = H3CellIndexer()
    val center = indexer.centerOf(indexer.cellFor(anchor.latitude, anchor.longitude)).roundedToWirePrecision()
    return anchor.copy(latitude = center.latitude, longitude = center.longitude)
}

fun main(args: Array<String>) {
    val spec = WorkloadSpec.load()
    val options = RunOptions.parse(args.toList(), spec)
    val accounts = AccountBook.load(options.accountsPath)
    options.validateAccounts(accounts)
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
