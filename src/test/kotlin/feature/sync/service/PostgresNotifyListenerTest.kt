/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.sync.service

import com.mozgobolt.core.database.DatabaseFactory
import com.mozgobolt.core.database.ExposedTransactionalRunner
import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.feature.sync.data.repository.SYNC_NOTIFY_CHANNEL
import com.mozgobolt.feature.sync.data.repository.SyncRepositoryI
import com.mozgobolt.feature.sync.domain.SyncEventHub
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncHint
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.user.data.database.UserEntity
import com.mozgobolt.feature.user.domain.model.UserRole
import io.github.resilience4j.core.IntervalFunction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun `a committed repository event reaches the live hub with its origin device`() {
        skipIfNoDocker()

        withSyncRepositoryDatabase { postgres, tx ->
            val hub = InMemorySyncEventHub()
            val listener =
                PostgresNotifyListener(
                    dbUrl = postgres.jdbcUrl,
                    dbUser = postgres.username,
                    dbPassword = postgres.password,
                    syncEventHub = hub,
                    backgroundScope = ApplicationBackgroundScope(),
                )
            listener.start()
            try {
                val (hint, committedEventIds) = awaitRepositoryHint(tx, hub)
                assertTrue(hint.eventId in committedEventIds)
                assertEquals("sync-test-device", hint.originDeviceId)
            } finally {
                listener.stop()
            }
        }
    }

    private fun withSyncRepositoryDatabase(block: (PostgreSQLContainer, TransactionalRunner) -> Unit) {
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
        postgres.start()
        try {
            withHikariDataSource(postgres) { dataSource ->
                DatabaseFactory.runFlywayMigration(dataSource)
                withDatabase(dataSource) { database ->
                    val tx = ExposedTransactionalRunner(database)
                    seedSyncTestUser(tx)
                    block(postgres, tx)
                }
            }
        } finally {
            postgres.stop()
        }
    }

    private fun withHikariDataSource(
        postgres: PostgreSQLContainer,
        block: (DataSource) -> Unit,
    ) {
        val dataSource =
            DatabaseFactory.createHikariDataSource(
                dbUrl = postgres.jdbcUrl,
                dbUser = postgres.username,
                dbPassword = postgres.password,
                poolSize = 5,
            )
        try {
            block(dataSource)
        } finally {
            (dataSource as? AutoCloseable)?.close()
        }
    }

    private fun withDatabase(
        dataSource: DataSource,
        block: (Database) -> Unit,
    ) {
        val database = DatabaseFactory.createDatabase(dataSource)
        try {
            block(database)
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    }

    private fun seedSyncTestUser(tx: TransactionalRunner) {
        runBlocking {
            tx.transactional {
                UserEntity.new {
                    email = "sync-listener@example.test"
                    name = "Sync listener"
                    passwordHash = "unused"
                    role = UserRole.BUYER
                }
            }
        }
    }

    private fun awaitRepositoryHint(
        tx: TransactionalRunner,
        hub: InMemorySyncEventHub,
    ): Pair<SyncHint, Set<Long>> =
        runBlocking {
            val received = async { hub.subscribe(1).first() }
            val committedEventIds = mutableSetOf<Long>()
            val hint =
                withTimeout(15.seconds) {
                    recordUntilListenerReceives(tx, received, committedEventIds)
                }
            hint to committedEventIds
        }

    private suspend fun recordUntilListenerReceives(
        tx: TransactionalRunner,
        received: Deferred<SyncHint>,
        committedEventIds: MutableSet<Long>,
    ): SyncHint {
        while (received.isActive) {
            val event =
                tx.transactional {
                    SyncRepositoryI().record(
                        userId = 1,
                        entityType = SyncEntityType.COMPANY,
                        entityId = 42,
                        operation = SyncOperation.UPSERT,
                        originDeviceId = "sync-test-device",
                    )
                }
            committedEventIds += event.id
            if (received.isActive) delay(50.milliseconds)
        }

        return received.await()
    }

    @Test
    fun `a NOTIFY on the sync channel is forwarded to the hub as userId to eventId`() {
        skipIfNoDocker()

        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
        postgres.start()
        try {
            val hub = InMemorySyncEventHub()
            val connectionAttempts = AtomicInteger()
            val listener =
                PostgresNotifyListener(
                    dbUrl = postgres.jdbcUrl,
                    dbUser = postgres.username,
                    dbPassword = postgres.password,
                    syncEventHub = hub,
                    backgroundScope = ApplicationBackgroundScope(),
                    openConnection = {
                        connectionAttempts.incrementAndGet()
                        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
                    },
                )
            listener.start()
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
                runBlocking { delay(200.milliseconds) }
                assertEquals(1, connectionAttempts.get(), "a repeated start must not open another LISTEN connection")
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

        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
        postgres.start()
        try {
            val hub = InMemorySyncEventHub()
            val listener =
                PostgresNotifyListener(
                    dbUrl = postgres.jdbcUrl,
                    dbUser = postgres.username,
                    dbPassword = postgres.password,
                    syncEventHub = hub,
                    backgroundScope = ApplicationBackgroundScope(),
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

        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
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
                    backgroundScope = ApplicationBackgroundScope(),
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

    @Test
    fun `malformed notifications and handler failures do not stop later valid notifications`() {
        skipIfNoDocker()

        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
        postgres.start()
        try {
            val hub = FailingOnceSyncEventHub()
            val listener =
                PostgresNotifyListener(
                    dbUrl = postgres.jdbcUrl,
                    dbUser = postgres.username,
                    dbPassword = postgres.password,
                    syncEventHub = hub,
                    backgroundScope = ApplicationBackgroundScope(),
                )
            listener.start()

            try {
                val hint =
                    runBlocking {
                        withTimeout(15.seconds) {
                            coroutineScope {
                                val received = async { hub.subscribe(7).first() }
                                while (received.isActive) {
                                    fireNotifies(
                                        postgres,
                                        listOf(
                                            "not-a-user:17",
                                            "7:not-a-number",
                                            "7:98:device-A",
                                            "7:99:",
                                        ),
                                    )
                                    delay(50.milliseconds)
                                }
                                received.await()
                            }
                        }
                    }

                assertEquals(99L, hint.eventId)
                assertNull(hint.originDeviceId)
                assertEquals(listOf(98L, 99L), hub.attemptedEventIds)
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

    private fun fireNotifies(
        postgres: PostgreSQLContainer,
        payloads: List<String>,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.autoCommit = false
            sendNotifyBatch(connection, payloads)
            connection.commit()
        }
    }

    private fun sendNotifyBatch(
        connection: java.sql.Connection,
        payloads: List<String>,
    ) {
        connection.prepareStatement("SELECT pg_notify(?, ?)").use { statement ->
            payloads.forEach { payload -> sendNotify(statement, payload) }
        }
    }

    private fun sendNotify(
        statement: PreparedStatement,
        payload: String,
    ) {
        statement.setString(1, SYNC_NOTIFY_CHANNEL)
        statement.setString(2, payload)
        statement.executeQuery().use { result -> check(result.next()) }
    }
}

private class FailingOnceSyncEventHub : SyncEventHub {
    val attemptedEventIds = mutableListOf<Long>()
    private val publishedHint = CompletableDeferred<SyncHint>()

    override fun publish(
        userId: Int,
        eventId: Long,
        originDeviceId: String?,
    ) {
        attemptedEventIds += eventId
        if (eventId == 98L) error("simulated transient hub failure")
        publishedHint.complete(SyncHint(eventId, originDeviceId))
    }

    override fun subscribe(userId: Int): Flow<SyncHint> =
        flow {
            emit(publishedHint.await())
        }
}
