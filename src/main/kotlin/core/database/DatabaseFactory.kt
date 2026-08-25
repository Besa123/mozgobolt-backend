package com.besa.shelflife.core.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

object DatabaseFactory {
    private const val DEFAULT_POOL_SIZE = 10
    private const val MIN_IDLE_CONNECTIONS = 2
    private val CONNECTION_TIMEOUT = 10.seconds
    private val VALIDATION_TIMEOUT = 5.seconds
    private val IDLE_TIMEOUT = 10.minutes
    private val MAX_LIFETIME = 30.minutes
    private val KEEPALIVE_TIME = 5.minutes
    private val LEAK_DETECTION_THRESHOLD = 30.seconds

    fun createHikariDataSource(
        dbUrl: String,
        dbUser: String,
        dbPassword: String,
        poolSize: Int = DEFAULT_POOL_SIZE,
    ): DataSource {
        val config =
            HikariConfig().apply {
                driverClassName = "org.postgresql.Driver"
                jdbcUrl = dbUrl
                username = dbUser
                password = dbPassword
                poolName = "ShelfLife-Pool"
                maximumPoolSize = poolSize
                minimumIdle = MIN_IDLE_CONNECTIONS
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
                connectionTimeout = CONNECTION_TIMEOUT.inWholeMilliseconds
                validationTimeout = VALIDATION_TIMEOUT.inWholeMilliseconds
                idleTimeout = IDLE_TIMEOUT.inWholeMilliseconds
                maxLifetime = MAX_LIFETIME.inWholeMilliseconds
                keepaliveTime = KEEPALIVE_TIME.inWholeMilliseconds
                leakDetectionThreshold = LEAK_DETECTION_THRESHOLD.inWholeMilliseconds
            }

        return HikariDataSource(config).also {
            logger.info { "Database pool started (max=$poolSize, minIdle=2)" }
        }
    }

    fun createDatabase(dataSource: DataSource): Database =
        Database.connect(dataSource).also {
            logger.info { "Exposed ORM connected to database" }
        }

    fun runFlywayMigration(database: DataSource) {
        val flyway =
            Flyway
                .configure()
                .dataSource(database)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .validateOnMigrate(true)
                .load()

        val result = flyway.migrate()
        logger.info { "Flyway migration complete: ${result.migrationsExecuted} migrations applied" }
    }
}
