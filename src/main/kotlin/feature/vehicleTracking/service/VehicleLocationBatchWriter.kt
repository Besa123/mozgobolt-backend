package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.utility.functions.runSuspendCatching
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationBuffer
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}
private val DEFAULT_FLUSH_INTERVAL = 7.seconds

/**
 * Slow path: drains the in-memory telemetry buffer on a fixed interval and bulk-inserts it into
 * `vehicle_locations`. Real-time delivery to buyers already happened via the fast-path hub before
 * anything reached this buffer — this class only backs durable history, so a lost batch on crash
 * (buffer is in-memory) degrades history, never live visibility.
 */
class VehicleLocationBatchWriter(
    private val buffer: VehicleLocationBuffer,
    private val repository: VehicleLocationRepository,
    private val tx: TransactionalRunner,
    private val flushInterval: Duration = DEFAULT_FLUSH_INTERVAL,
) {
    private val exceptionHandler =
        CoroutineExceptionHandler { _, failure ->
            logger.error(failure) { "VehicleLocationBatchWriter terminated unexpectedly and will not restart" }
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private var job: Job? = null

    fun start() {
        job =
            scope.launch {
                while (isActive) {
                    delay(flushInterval)
                    // A single failed flush (a transient DB blip) must not permanently kill this
                    // loop — that batch's data is lost either way (buffer is in-memory, already
                    // an accepted trade-off, see class doc), but every *future* interval must
                    // still get a chance. Without this, one blip silently stops all durable
                    // history until the process restarts.
                    runSuspendCatching { flush() }
                        .onFailure {
                            logger.error(
                                it,
                            ) { "Failed to persist a telemetry batch; retrying next interval" }
                        }
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun flush() {
        val batch = buffer.drainAll()
        if (batch.isEmpty()) return

        tx.transactional { repository.insertAll(batch) }
    }
}
