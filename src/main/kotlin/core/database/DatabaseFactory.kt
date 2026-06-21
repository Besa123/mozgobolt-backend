package com.besa.boardShare.core.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

object DatabaseFactory {
    fun createHikariDataSource(
        dbUrl: String,
        dbUser: String,
        dbPassword: String,
    ): DataSource {
        val config = HikariConfig().apply {
            driverClassName = "org.postgresql.Driver"
            jdbcUrl = dbUrl
            username = dbUser
            password = dbPassword
            maximumPoolSize = 10
            isAutoCommit = false
        }

        return HikariDataSource(config)
    }

    fun createDatabase(
        dataSource: DataSource
    ): Database {
        return Database.connect(dataSource)
    }

    fun runFlywayMigration(
        database: DataSource
    ) {
        val flyway = Flyway.configure()
            .dataSource(database)
            .load()

        flyway.migrate()
    }
}