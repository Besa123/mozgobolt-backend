package com.mozgobolt.core.utility

import com.mozgobolt.core.utility.functions.runSuspendCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

// Purely a maintenance concern with no user-visible latency requirement — a coarse interval
// keeps this from ever being a meaningful cost, while still reclaiming idle entries well within
// a single long-running process's lifetime rather than only at restart.
internal val DEFAULT_SWEEP_INTERVAL = 15.minutes

/**
 * Periodically reclaims entries from a `ConcurrentHashMap` of per-key broadcast
 * [MutableSharedFlow]s (e.g. `InMemoryVehicleLocationHub.flowsByCell`,
 * `InMemoryVehiclePingHub.flowsByVendorUserId`) once nobody is subscribed to them anymore, so the
 * map doesn't hold one entry forever per distinct key ever seen (a geographic cell, a vendor id,
 * ...).
 *
 * Safe specifically because every flow this sweeps is constructed with `replay = 0` in its owning
 * hub — a subscriber only ever sees emissions from after it attaches, so a flow with zero current
 * subscribers holds nothing anyone would miss by being evicted and lazily recreated (via the
 * owning hub's own `computeIfAbsent`) the next time something subscribes to that key again.
 *
 * The eviction is a single [ConcurrentHashMap.compute] call per key, not a separate
 * check-then-remove: `compute`/`computeIfAbsent` on the same key are documented to be atomic and
 * mutually exclusive per key, so this can never race a concurrent subscriber's own
 * `computeIfAbsent` for that key. Either the subscriber's `computeIfAbsent` runs first, attaches,
 * and this sweep's `compute` then sees a non-zero `subscriptionCount` and keeps the flow — or this
 * sweep's `compute` runs first, sees zero subscribers, and evicts, in which case the subscriber's
 * subsequent `computeIfAbsent` finds the key absent and creates (and attaches to) a fresh flow
 * instead. Either ordering is safe; there is no interleaving where a subscriber ends up attached
 * to a flow that then silently vanishes from the map out from under it. Do not "simplify" this
 * into a plain read-then-remove — that reintroduces exactly this race.
 */
class IdleSharedFlowSweeper<K : Any, T>(
    private val flows: ConcurrentHashMap<K, MutableSharedFlow<T>>,
    private val sweepInterval: Duration = DEFAULT_SWEEP_INTERVAL,
) {
    private val exceptionHandler =
        CoroutineExceptionHandler { _, failure ->
            logger.error(failure) { "IdleSharedFlowSweeper terminated unexpectedly and will not restart" }
        }

    // Default, not IO: a sweep only ever touches in-memory map/flow state, never blocking I/O.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    private var job: Job? = null

    fun start() {
        job =
            scope.launch {
                while (isActive) {
                    delay(sweepInterval)
                    // A single failed sweep must not permanently kill this loop — every future
                    // interval still gets a chance to run, mirroring this codebase's other
                    // periodic background loops (e.g. VehicleLocationRetentionPurger).
                    runSuspendCatching { sweepOnce() }
                        .onFailure { logger.error(it) { "Failed to sweep idle flows; retrying next interval" } }
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    internal fun sweepOnce() {
        flows.keys.toList().forEach { key ->
            flows.compute(key) { _, flow -> flow?.takeIf { it.subscriptionCount.value > 0 } }
        }
    }
}
