/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.core.utility.functions.runSuspendCatching
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}
private val DEFAULT_RECONCILIATION_INTERVAL = 30.seconds

/** Rehydrates and periodically repairs this instance's volatile live-location cache from PostgreSQL. */
class VehicleLocationStateReconciler(
    private val repository: VehicleLocationRepository,
    private val tx: TransactionalRunner,
    private val hub: InMemoryVehicleLocationHub,
    private val backgroundScope: ApplicationBackgroundScope,
    private val clock: Clock = Clock.systemUTC(),
    private val reconciliationInterval: Duration = DEFAULT_RECONCILIATION_INTERVAL,
) {
    private var job: Job? = null

    init {
        require(reconciliationInterval > Duration.ZERO) { "Reconciliation interval must be positive" }
    }

    /** Loads persisted state before the instance begins serving live-map requests. */
    suspend fun reconcile() {
        val asOf = clock.instant()
        val snapshot =
            tx.transactionalReadOnlySnapshot {
                repository.findLiveStateSnapshot(
                    cutoff = asOf.minusMillis(LIVE_LOCATION_STALE_AFTER_MILLIS),
                    asOf = asOf,
                )
            }
        hub.reconcileSnapshot(snapshot, asOf.toEpochMilli())
    }

    /** Repairs events missed during a Valkey reconnect from the durable latest-history snapshot. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return

        job =
            backgroundScope.launch(CoroutineName("vehicle-location-state-reconciler")) {
                while (isActive) {
                    delay(reconciliationInterval)
                    runSuspendCatching { reconcile() }
                        .onError { failure ->
                            logger.error(failure) {
                                "Failed to reconcile the live-location cache; retrying next interval"
                            }
                        }
                }
            }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }
}
