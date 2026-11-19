/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ln
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// How long after connecting a map is compared with the fleet, giving the snapshot time to arrive.
private val MAP_SETTLE_DELAY = 2.seconds
private val MIN_SESSION = 10.seconds
private val MAX_THINK_TIME = 5.seconds

// Reopening the map after a pan/zoom lands near, not exactly on, where the buyer was.
private const val PAN_SIGMA_KM = 1.5

/** A buyer with the place they usually look at. */
internal data class Viewer(
    val buyer: BuyerAccount,
    val home: GeoPoint,
    /** The map currently open, or `null` between sessions. */
    val live: MutableStateFlow<LiveWatch?> = MutableStateFlow(null),
)

internal data class LiveWatch(
    val sessionId: Int,
    val map: StateFlow<LiveMapState>,
)

private data class WatchPlan(
    val center: GeoPoint,
    val watchFor: Duration,
)

/**
 * Every buyer behaves like a real app user: opens the live map, watches for a while, closes it, and
 * later opens it again near the same spot. That connect/disconnect churn is what exercises
 * subscription registration and cleanup in the server, which static long-lived connections never do.
 * Session lengths are exponentially distributed (memoryless) around the mean, like real app usage.
 */
internal class ViewerCrowd(
    private val viewers: List<Viewer>,
    private val apis: List<LiveApi>,
    private val sink: ObservationSink,
    private val radiusKm: Double,
    /** Mean time one map stays open before the buyer closes it; `null` keeps every map open for the whole run. */
    private val meanSession: Duration?,
    private val seed: Long,
) {
    private val sessionIds = AtomicInteger()

    init {
        require(apis.isNotEmpty()) { "At least one app node is required" }
    }

    /** Runs every viewer until cancelled; initial connections are spread uniformly over [rampUp]. */
    suspend fun run(rampUp: Duration) =
        coroutineScope {
            viewers.forEachIndexed { index, viewer ->
                val api = apis[index % apis.size]
                val random = randomFor(viewer)
                launch {
                    delay(rampUp * random.nextDouble())
                    viewer.plans(random).collect { plan -> viewer.watch(api, plan) }
                }
            }
        }

    /** Compares every currently open map with the fleet's final positions, once the fleet has stopped. */
    fun checkFinalMaps() {
        viewers.mapNotNull { it.live.value }.forEach { watch ->
            sink.record(Observation.MapChecked(watch.sessionId, CheckPhase.FINAL, watch.map.value))
        }
    }

    private fun randomFor(viewer: Viewer) =
        Random(
            seed xor
                viewer.buyer.credentials.userId
                    .toLong(),
        )

    private fun Viewer.plans(random: Random): Flow<WatchPlan> =
        flow {
            emit(WatchPlan(home, exponentialWatchDuration(random)))
            emitAll(
                generateSequence { nextPlanUnlessMapsStayOpen(random) }
                    .asFlow()
                    .onEach { delay(MAX_THINK_TIME * random.nextDouble()) },
            )
        }

    private fun Viewer.nextPlanUnlessMapsStayOpen(random: Random): WatchPlan? =
        meanSession?.let {
            WatchPlan(
                center = home.scatteredBy(PAN_SIGMA_KM, random).roundedToWirePrecision(),
                watchFor = exponentialWatchDuration(random),
            )
        }

    private fun exponentialWatchDuration(random: Random): Duration =
        meanSession?.let { (it * -ln(1.0 - random.nextDouble())).coerceAtLeast(MIN_SESSION) } ?: Duration.INFINITE

    private suspend fun Viewer.watch(
        api: LiveApi,
        plan: WatchPlan,
    ) = coroutineScope {
        val sessionId = sessionIds.incrementAndGet()
        val map = MutableStateFlow(LiveMapState.EMPTY)
        sink.record(
            Observation.ViewerConnecting(
                sessionId = sessionId,
                center = plan.center,
                radiusKm = radiusKm,
                at = TimeSource.Monotonic.markNow(),
                nodeName = api.nodeName,
            ),
        )

        val feed =
            api
                .liveFeed(buyer, plan.center, radiusKm)
                .onEach { event ->
                    sink.record(
                        Observation.ViewerEventReceived(
                            sessionId = sessionId,
                            event = event,
                            at = TimeSource.Monotonic.markNow(),
                            nodeName = api.nodeName,
                        ),
                    )
                    map.update { it.apply(event) }
                }.launchIn(this)
        val settledCheck =
            launch {
                delay(MAP_SETTLE_DELAY)
                sink.record(Observation.MapChecked(sessionId, CheckPhase.SETTLED, map.value))
            }
        live.value = LiveWatch(sessionId, map)

        try {
            withTimeoutOrNull(plan.watchFor) { feed.join() }
        } finally {
            live.value = null
            settledCheck.cancel()
            feed.cancel()
            sink.record(Observation.ViewerLeft(sessionId))
        }
    }
}
