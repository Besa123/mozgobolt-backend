/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.core.utility.functions.runSuspendCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}
private val DEFAULT_MAINTENANCE_INTERVAL = 1.minutes

/** Reclaims expired in-memory assignment fences and stale live positions. */
class VehicleLocationHubMaintenanceSweeper(
    private val hub: InMemoryVehicleLocationHub,
    private val backgroundScope: ApplicationBackgroundScope,
    private val sweepInterval: Duration = DEFAULT_MAINTENANCE_INTERVAL,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return

        job =
            backgroundScope.launch(dispatcher + CoroutineName("vehicle-location-hub-maintenance")) {
                while (isActive) {
                    delay(sweepInterval)
                    runSuspendCatching {
                        hub.sweepExpiredAssignmentFences()
                        hub.sweepStaleLocations()
                    }.onError { failure ->
                        logger.error(failure) { "Failed to sweep vehicle-location hub state; retrying next interval" }
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
