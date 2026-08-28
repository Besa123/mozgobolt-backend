package com.shelflife.feature.sync.service

import com.shelflife.core.skipIfNoDocker
import com.shelflife.feature.sync.data.repository.SYNC_NOTIFY_CHANNEL
import io.github.resilience4j.core.IntervalFunction
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Exercises the actual production wiring end to end: a raw SQL `NOTIFY` on the sync channel,
 * received by a real [PostgresNotifyListener] holding a real `LISTEN` connection, forwarded into
 * a real [InMemorySyncEventHub]. [SyncServiceI] itself no longer publishes to the hub directly
 * (see its class doc) — this is the only path that hint delivery actually takes, so it's the one
 * that needs a live-Postgres test, not a fake.
 */
class PostgresNotifyListenerTest {
    @Test
    fun `a NOTIFY on the sync channel is forwarded to the hub as userId to eventId`() {
        skipIfNoDocker()

        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
        postgres.start()
        try {
            val hub = InMemorySyncEventHub()
            val listener =
                PostgresNotifyListener(
                    dbUrl = postgres.jdbcUrl,
                    dbUser = postgres.username,
                    dbPassword = postgres.password,
                    syncEventHub = hub,
                )
            listener.start()

            try {
                val hint =
                    runBlocking {
                        withTimeout(15.seconds) {
                            coroutineScope {
                                val received = async { hub.subscribe(7).first() }
                                // The listener's LISTEN connection has no externally observable
                                // "ready" signal, and a NOTIFY fired before it's up is simply
                                // lost — Postgres doesn't queue one for a channel nobody's
                                // listening on yet. Retry firing it until received completes.
                                while (received.isActive) {
                                    fireNotify(postgres, "7:99")
                                    delay(200.milliseconds)
                                }
                                received.await()
                            }
                        }
                    }

                assertEquals(99L, hint.eventId)
            } finally {
                listener.stop()
            }
        } finally {
            postgres.stop()
        }
    }

    @Test
    fun `a NOTIFY payload's third field is forwarded as the hint's origin device id`() {
        skipIfNoDocker()

        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
        postgres.start()
        try {
            val hub = InMemorySyncEventHub()
            val listener =
                PostgresNotifyListener(
                    dbUrl = postgres.jdbcUrl,
                    dbUser = postgres.username,
                    dbPassword = postgres.password,
                    syncEventHub = hub,
                )
            listener.start()

            try {
                val hint =
                    runBlocking {
                        withTimeout(15.seconds) {
                            coroutineScope {
                                val received = async { hub.subscribe(7).first() }
                                while (received.isActive) {
                                    fireNotify(postgres, "7:99:device-A")
                                    delay(200.milliseconds)
                                }
                                received.await()
                            }
                        }
                    }

                assertEquals(99L, hint.eventId)
                assertEquals("device-A", hint.originDeviceId)
            } finally {
                listener.stop()
            }
        } finally {
            postgres.stop()
        }
    }

    @Test
    fun `after connection failures, the listener backs off and recovers instead of dying`() {
        skipIfNoDocker()

        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
        postgres.start()
        try {
            val hub = InMemorySyncEventHub()
            var connectionAttempts = 0
            val simulatedFailures = 2

            val listener =
                PostgresNotifyListener(
                    dbUrl = postgres.jdbcUrl,
                    dbUser = postgres.username,
                    dbPassword = postgres.password,
                    syncEventHub = hub,
                    // The first two attempts fail exactly like a dropped/refused connection
                    // would; the real DriverManager only gets called from the third attempt on.
                    openConnection = {
                        connectionAttempts++
                        if (connectionAttempts <= simulatedFailures) {
                            throw SQLException("simulated connection failure #$connectionAttempts")
                        }
                        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
                    },
                    // A constant, tiny interval — this test cares that a reconnect happens and
                    // recovers, not about exercising the real exponential-backoff-with-jitter
                    // curve (that math is Resilience4j's, not this codebase's, to get right).
                    reconnectIntervalFunction = IntervalFunction.of(50),
                )
            listener.start()

            try {
                val hint =
                    runBlocking {
                        withTimeout(15.seconds) {
                            coroutineScope {
                                val received = async { hub.subscribe(7).first() }
                                while (received.isActive) {
                                    fireNotify(postgres, "7:99")
                                    delay(50.milliseconds)
                                }
                                received.await()
                            }
                        }
                    }

                assertEquals(99L, hint.eventId)
                // Proves the recovery actually happened via the failure path, not that the fake
                // was simply never exercised.
                assertTrue(connectionAttempts > simulatedFailures)
            } finally {
                listener.stop()
            }
        } finally {
            postgres.stop()
        }
    }

    private fun fireNotify(
        postgres: PostgreSQLContainer,
        payload: String,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("NOTIFY $SYNC_NOTIFY_CHANNEL, '$payload'") }
        }
    }
}
