package com.shelflife.core

import com.shelflife.core.database.DatabaseFactory
import com.shelflife.core.database.ExposedTransactionalRunner
import com.shelflife.core.database.TransactionalRunner
import com.shelflife.feature.user.data.database.UserEntity
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

fun skipIfNoDocker() {
    assumeTrue(
        "Docker is not available locally — skipping (this still runs in CI)",
        DockerClientFactory.instance().isDockerAvailable,
    )
}

/**
 * Runs the full Flyway migration history (including seed data) against a throwaway Postgres, and
 * seeds two generic `users` rows — they land on ids 1 and 2, in insertion order on an otherwise-
 * empty `SERIAL` primary key, which every feature's integration tests rely on when hardcoding
 * `userId` 1/2 for a private/owned resource (e.g. `products.user_id`'s foreign key). A real user
 * always exists in production (the id comes off an authenticated JWT); nothing seeds one here
 * automatically. Shared by every feature's own `withRealXDatabase` helper — don't re-copy this.
 */
fun withRealDatabase(block: suspend (Database, TransactionalRunner) -> Unit) {
    val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
    postgres.start()

    try {
        val dataSource =
            DatabaseFactory.createHikariDataSource(
                dbUrl = postgres.jdbcUrl,
                dbUser = postgres.username,
                dbPassword = postgres.password,
                poolSize = 5,
            )

        try {
            DatabaseFactory.runFlywayMigration(dataSource)
            val database = DatabaseFactory.createDatabase(dataSource)
            val tx = ExposedTransactionalRunner(database)

            runBlocking {
                tx.transactional {
                    UserEntity.new {
                        email = "test-user-1@example.com"
                        name = "Test User One"
                        passwordHash = "unused"
                    }
                    UserEntity.new {
                        email = "test-user-2@example.com"
                        name = "Test User Two"
                        passwordHash = "unused"
                    }
                }
                block(database, tx)
            }
        } finally {
            (dataSource as? AutoCloseable)?.close()
        }
    } finally {
        postgres.stop()
    }
}
