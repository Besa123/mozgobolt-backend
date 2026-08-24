package com.besa.shelflife.core.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

private val logger = KotlinLogging.logger {}

object DatabaseFactory {
    fun createHikariDataSource(
        dbUrl: String,
        dbUser: String,
        dbPassword: String,
        poolSize: Int = 10,
    ): DataSource {
        val config = HikariConfig().apply {
            driverClassName = "org.postgresql.Driver"
            jdbcUrl = dbUrl
            username = dbUser
            password = dbPassword
            poolName = "ShelfLife-Pool"
            maximumPoolSize = poolSize
            minimumIdle = 2
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            connectionTimeout = 10_000
            validationTimeout = 5_000
            idleTimeout = 600_000
            maxLifetime = 1_800_000
            keepaliveTime = 300_000
            leakDetectionThreshold = 30_000
        }

        return HikariDataSource(config).also {
            logger.info { "Database pool started (max=$poolSize, minIdle=2)" }
        }
    }

    fun createDatabase(
        dataSource: DataSource
    ): Database {
        return Database.connect(dataSource).also {
            logger.info { "Exposed ORM connected to database" }
        }
    }

    fun runFlywayMigration(
        database: DataSource
    ) {
        val flyway = Flyway.configure()
            .dataSource(database)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .validateOnMigrate(true)
            .load()

        val result = flyway.migrate()
        logger.info { "Flyway migration complete: ${result.migrationsExecuted} migrations applied" }
    }
}