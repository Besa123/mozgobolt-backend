package com.besa.boardShare.core.database

import com.besa.boardShare.core.utility.module.dotEnv.DotEnv
import com.besa.boardShare.feature.user.data.database.RefreshTokensTable
import com.besa.boardShare.feature.user.data.database.UsersTable
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

object DatabaseFactory {
    fun createDatabase(): Database {
        val config = HikariConfig().apply {
            driverClassName = "org.postgresql.Driver"
            jdbcUrl = DotEnv.INSTANCE.get("DB_URL") ?: error("Missing DB_URL")
            username = DotEnv.INSTANCE.get("DB_USER") ?: error("Missing DB_USER")
            password = DotEnv.INSTANCE.get("DB_PASSWORD") ?: error("Missing DB_PASSWORD")
            maximumPoolSize = 10
            isAutoCommit = false
        }

        val dataSource = HikariDataSource(config)
        return Database.connect(dataSource)
    }

    fun schemaInitializer(
        database: Database
    ) {
        transaction(database) {
            SchemaUtils.create(
                UsersTable,
                RefreshTokensTable,
            )
        }
    }
}