/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.sync.service

import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.core.utility.functions.runSuspendCatching
import com.mozgobolt.feature.sync.data.repository.SYNC_NOTIFY_CHANNEL
import com.mozgobolt.feature.sync.domain.SyncEventHub
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.core.IntervalFunction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.postgresql.PGConnection
import java.sql.Connection
import java.sql.DriverManager
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}
private val POLL_TIMEOUT_MILLIS = 10.seconds.inWholeMilliseconds.toInt()
private val INITIAL_RECONNECT_DELAY = 1.seconds
private val MAX_RECONNECT_DELAY = 30.seconds
private const val RECONNECT_BACKOFF_MULTIPLIER = 2.0

class PostgresNotifyListener(
    private val dbUrl: String,
    private val dbUser: String,
    private val dbPassword: String,
    private val syncEventHub: SyncEventHub,
    private val backgroundScope: ApplicationBackgroundScope,
    private val openConnection: () -> Connection = { DriverManager.getConnection(dbUrl, dbUser, dbPassword) },
    private val reconnectIntervalFunction: IntervalFunction =
        IntervalFunction.ofExponentialRandomBackoff(
            INITIAL_RECONNECT_DELAY.inWholeMilliseconds,
            RECONNECT_BACKOFF_MULTIPLIER,
            MAX_RECONNECT_DELAY.inWholeMilliseconds,
        ),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var consecutiveFailures = 0

    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return

        job =
            backgroundScope.launch(CoroutineName("postgres-notify-listener")) {
                val retryingNotifications =
                    connect().retryWhen { failure, _ ->
                        consecutiveFailures++
                        val backoff = reconnectIntervalFunction.apply(consecutiveFailures).milliseconds
                        logger.warn(failure) {
                            "Postgres NOTIFY listener lost its connection " +
                                "(attempt $consecutiveFailures); retrying in $backoff"
                        }
                        delay(backoff)
                        true
                    }
                retryingNotifications.onEach { payload -> handle(payload) }.collect()
            }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    private fun connect(): Flow<String> =
        flow {
            val connection = openConnection()
            try {
                connection.createStatement().use { it.execute("LISTEN $SYNC_NOTIFY_CHANNEL") }
                consecutiveFailures = 0
                logger.info { "Postgres NOTIFY listener connected (LISTEN $SYNC_NOTIFY_CHANNEL)" }

                val pgConnection = connection.unwrap(PGConnection::class.java)
                while (true) {
                    val notifications =
                        runInterruptible(ioDispatcher) { pgConnection.getNotifications(POLL_TIMEOUT_MILLIS) }
                    notifications?.forEach { emit(it.parameter) }
                }
            } finally {
                runSuspendCatching { connection.close() }
            }
        }

    private fun handle(payload: String) {
        try {
            val parts = payload.split(":", limit = 3)
            val userId = parts.getOrNull(0)?.toIntOrNull()
            val eventId = parts.getOrNull(1)?.toLongOrNull()
            val originDeviceId = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }

            if (userId == null || eventId == null) {
                logger.warn { "Postgres NOTIFY payload could not be parsed as userId:eventId[:deviceId]" }
                return
            }

            syncEventHub.publish(userId, eventId, originDeviceId)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.error(e) { "Failed to handle Postgres NOTIFY payload" }
        }
    }
}
