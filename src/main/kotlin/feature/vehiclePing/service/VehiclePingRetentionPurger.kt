/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehiclePing.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.core.utility.functions.runSuspendCatching
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

private val logger = KotlinLogging.logger {}
private val DEFAULT_PURGE_CHECK_INTERVAL = 1.minutes

class VehiclePingRetentionPurger(
    private val repository: VehiclePingRepository,
    private val tx: TransactionalRunner,
    private val backgroundScope: ApplicationBackgroundScope,
    private val clock: Clock = Clock.systemUTC(),
    private val checkInterval: Duration = DEFAULT_PURGE_CHECK_INTERVAL,
    private val retentionPeriod: Duration = PING_COOLDOWN,
) {
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return

        job =
            backgroundScope.launch(CoroutineName("vehicle-ping-retention-purger")) {
                while (isActive) {
                    runSuspendCatching { purge() }
                        .onFailure {
                            logger.error(it) { "Failed to purge expired vehicle ping state; retrying next interval" }
                        }
                    delay(checkInterval)
                }
            }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun purge(): Int {
        val cutoff = clock.instant().minus(retentionPeriod.toJavaDuration())
        return tx.transactional { repository.purgeOlderThan(cutoff) }
    }
}
