package com.shelflife.feature.sync.service

import com.shelflife.core.utility.functions.runSuspendCatching
import com.shelflife.feature.sync.data.repository.SYNC_NOTIFY_CHANNEL
import com.shelflife.feature.sync.domain.SyncEventHub
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.core.IntervalFunction
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
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
    private val openConnection: () -> Connection = { DriverManager.getConnection(dbUrl, dbUser, dbPassword) },
    private val reconnectIntervalFunction: IntervalFunction =
        IntervalFunction.ofExponentialRandomBackoff(
            INITIAL_RECONNECT_DELAY.inWholeMilliseconds,
            RECONNECT_BACKOFF_MULTIPLIER,
            MAX_RECONNECT_DELAY.inWholeMilliseconds,
        ),
) {
    private var consecutiveFailures = 0

    private val exceptionHandler =
        CoroutineExceptionHandler { _, failure ->
            logger.error(failure) { "PostgresNotifyListener terminated unexpectedly and will not restart" }
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private var job: Job? = null

    fun start() {
        job =
            connect()
                .retryWhen { failure, _ ->
                    consecutiveFailures++
                    val backoff = reconnectIntervalFunction.apply(consecutiveFailures).milliseconds
                    logger.warn(failure) {
                        "Postgres NOTIFY listener lost its connection " +
                            "(attempt $consecutiveFailures); retrying in $backoff"
                    }
                    delay(backoff)
                    true
                }.onEach { payload -> handle(payload) }
                .launchIn(scope)
    }

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
                        runInterruptible(Dispatchers.IO) { pgConnection.getNotifications(POLL_TIMEOUT_MILLIS) }
                    notifications?.forEach { emit(it.parameter) }
                }
            } finally {
                runSuspendCatching { connection.close() }
            }
        }

    private fun handle(payload: String) {
        val parts = payload.split(":", limit = 3)
        val userId = parts.getOrNull(0)?.toIntOrNull()
        val eventId = parts.getOrNull(1)?.toLongOrNull()
        val originDeviceId = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }

        if (userId == null || eventId == null) {
            logger.warn { "Postgres NOTIFY payload didn't parse as 'userId:eventId[:deviceId]': $payload" }
            return
        }

        syncEventHub.publish(userId, eventId, originDeviceId)
    }
}
