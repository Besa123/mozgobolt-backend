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
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

private val logger = KotlinLogging.logger {}
private val DEFAULT_PURGE_CHECK_INTERVAL = 24.hours

// A completed driving session's location history is kept only long enough to be useful for
// payroll/hours verification — the user's own stated reason for retaining it at all — not as
// permanent analytics. The 30-day clock starts when a session ends, not when each point was
// recorded. A separate stale-session reaper ends assignments after 12 hours without accepted
// telemetry so an abandoned assignment cannot retain history indefinitely.
internal val LOCATION_RETENTION_PERIOD = 30.days

/**
 * Background purge for `vehicle_locations`, mirroring [VehicleLocationBatchWriter]'s periodic loop.
 * Runs on a much coarser interval than the batch writer (a day, not seconds) —
 * a 30-day retention window has no need for finer-grained checking.
 */
class VehicleLocationRetentionPurger(
    private val repository: VehicleLocationRepository,
    private val tx: TransactionalRunner,
    private val backgroundScope: ApplicationBackgroundScope,
    private val checkInterval: Duration = DEFAULT_PURGE_CHECK_INTERVAL,
    private val retentionPeriod: Duration = LOCATION_RETENTION_PERIOD,
) {
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return

        job =
            backgroundScope.launch(CoroutineName("vehicle-location-retention-purger")) {
                while (isActive) {
                    delay(checkInterval)
                    // A single failed purge (a transient DB blip) must not permanently kill this
                    // loop, mirroring VehicleLocationBatchWriter's own reasoning — every future
                    // interval must still get a chance to run.
                    runSuspendCatching { purge() }
                        .onFailure {
                            logger.error(
                                it,
                            ) { "Failed to purge stale vehicle locations; retrying next interval" }
                        }
                }
            }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun purge(): Int {
        val cutoff = Instant.now().minus(retentionPeriod.toJavaDuration())
        return tx.transactional { repository.purgeOlderThan(cutoff) }
    }
}
