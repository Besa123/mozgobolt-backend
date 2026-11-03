/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.idempotency

import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.core.utility.functions.runSuspendCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger {}
private val DEFAULT_PURGE_CHECK_INTERVAL = 1.hours

class IdempotencyRetentionPurger(
    private val store: IdempotencyStore,
    private val backgroundScope: ApplicationBackgroundScope,
    private val clock: Clock = Clock.systemUTC(),
    private val checkInterval: Duration = DEFAULT_PURGE_CHECK_INTERVAL,
) {
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return

        job =
            backgroundScope.launch(CoroutineName("idempotency-retention-purger")) {
                while (isActive) {
                    runSuspendCatching { purge() }
                        .onFailure {
                            logger.error(it) {
                                "Failed to purge expired idempotency records; retrying next interval"
                            }
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

    suspend fun purge(): Int = store.purgeExpiredRecords(clock.instant())
}
