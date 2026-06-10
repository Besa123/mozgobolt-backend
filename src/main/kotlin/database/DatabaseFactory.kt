package com.besa.boardShare.database

import com.besa.boardShare.utility.module.DotEnv
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database

object DatabaseFactory {
    val database by lazy {
        val config = HikariConfig().apply {
            driverClassName = "org.postgresql.Driver"
            jdbcUrl = DotEnv.INSTANCE.get("DB_URL") ?: error("Missing DB_URL")
            username = DotEnv.INSTANCE.get("DB_USER") ?: error("Missing DB_USER")
            password = DotEnv.INSTANCE.get("DB_PASSWORD") ?: error("Missing DB_PASSWORD")
            maximumPoolSize = 10
            isAutoCommit = false
        }

        val dataSource = HikariDataSource(config)
        Database.connect(dataSource)
    }
}