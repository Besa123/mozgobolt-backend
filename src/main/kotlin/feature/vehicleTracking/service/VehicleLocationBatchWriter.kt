/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.core.utility.functions.runSuspendCatching
import com.mozgobolt.feature.vehicleTracking.domain.NoOpVehicleTrackingMetrics
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationBuffer
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationRepository
import com.mozgobolt.feature.vehicleTracking.domain.VehicleTrackingMetrics
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import java.sql.SQLException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}
private val DEFAULT_FLUSH_INTERVAL = 7.seconds

/**
 * Slow path: drains the in-memory telemetry buffer on a fixed interval and bulk-inserts it into
 * `vehicle_locations`. Real-time delivery to buyers already happened via the fast-path hub before
 * anything reached this buffer. A graceful stop attempts a final flush; crash, overflow, or a failed
 * final flush can still lose history because the buffer is process-local.
 */
class VehicleLocationBatchWriter(
    private val buffer: VehicleLocationBuffer,
    private val repository: VehicleLocationRepository,
    private val tx: TransactionalRunner,
    private val backgroundScope: ApplicationBackgroundScope,
    private val flushInterval: Duration = DEFAULT_FLUSH_INTERVAL,
    private val metrics: VehicleTrackingMetrics = NoOpVehicleTrackingMetrics,
) {
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return

        job =
            backgroundScope.launch(CoroutineName("vehicle-location-batch-writer")) {
                while (isActive) {
                    delay(flushInterval)
                    // A single failed flush must not permanently kill this loop. The buffer keeps
                    // the failed batch at the head of its bounded queue for the next interval.
                    runSuspendCatching { flush() }
                        .onError { failure ->
                            metrics.recordHistoryBatchRetry()
                            logger.error(failure) { "Failed to persist a telemetry batch; retrying next interval" }
                        }
                }
            }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Stops the periodic worker, waits for any claimed batch to be restored, then flushes once. */
    suspend fun stopAndFlush() {
        val writerJob =
            synchronized(this) {
                job.also {
                    job = null
                    it?.cancel()
                }
            }
        writerJob?.join()
        flush()
    }

    suspend fun flush() {
        val startedAtNanos = System.nanoTime()
        val batch = buffer.drainAll()
        if (batch.isEmpty()) {
            metrics.recordHistoryBatchFlush(persistedPoints = 0, elapsedNanos = System.nanoTime() - startedAtNanos)
            return
        }

        var wasBatchCompleted = false
        try {
            val skippedHistoryPoints = persistBatch(batch)
            buffer.completeBatch(batch)
            wasBatchCompleted = true
            metrics.recordHistoryBatchFlush(
                persistedPoints = batch.size - skippedHistoryPoints,
                elapsedNanos = System.nanoTime() - startedAtNanos,
            )

            if (skippedHistoryPoints > 0) {
                logger.warn {
                    "Skipped $skippedHistoryPoints telemetry history point(s) with stale or invalid references"
                }
            }
        } finally {
            if (!wasBatchCompleted) {
                buffer.restoreFailedBatch(batch)
            }
        }
    }

    private suspend fun persistBatch(updates: List<VehicleLocationUpdate>): Int {
        try {
            return tx.transactional { repository.insertAllForCurrentReferences(updates) }
        } catch (failure: ExposedSQLException) {
            return recoverRowFailure(failure, updates)
        } catch (failure: SQLException) {
            return recoverRowFailure(failure, updates)
        }
    }

    @Suppress("SqlInjection")
    private suspend fun recoverRowFailure(
        failure: Throwable,
        updates: List<VehicleLocationUpdate>,
    ): Int {
        if (!failure.isPermanentRowFailure()) throw failure
        if (updates.size == 1) {
            // Revalidate after the failed transaction has rolled back. Known stale/invalid rows
            // are skipped by the repository; an unrecognized persistent database constraint must
            // remain visible and keep the batch queued rather than being silently lost.
            return tx.transactional { repository.insertAllForCurrentReferences(updates) }
        }

        val split = updates.size / 2
        return persistBatch(updates.subList(0, split)) +
            persistBatch(updates.subList(split, updates.size))
    }

    private fun Throwable.isPermanentRowFailure(): Boolean =
        generateSequence(this) { it.cause }
            .filterIsInstance<SQLException>()
            .any { sqlException ->
                sqlException.sqlState?.startsWith("22") == true || sqlException.sqlState?.startsWith("23") == true
            }
}
