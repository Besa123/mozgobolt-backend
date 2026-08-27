package com.shelflife.feature.storageLocation

import com.shelflife.core.database.DatabaseFactory
import com.shelflife.core.database.ExposedTransactionalRunner
import com.shelflife.core.database.TransactionalRunner
import com.shelflife.feature.storageLocation.data.repository.StorageLocationRepositoryI
import com.shelflife.feature.storageLocation.service.StorageLocationServiceI
import com.shelflife.feature.user.data.database.UserEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

data class StorageLocationTestHarness(
    val repository: StorageLocationRepositoryI,
    val service: StorageLocationServiceI,
    val tx: TransactionalRunner,
)

fun skipIfNoDocker() {
    assumeTrue(
        "Docker is not available locally — skipping (this still runs in CI)",
        DockerClientFactory.instance().isDockerAvailable,
    )
}

/**
 * Runs the V1–V3 migrations against a throwaway Postgres and seeds two users (ids 1 and 2)
 * for storage location tests that hardcode `userId` 1 and 2.
 */
fun withRealStorageLocationDatabase(block: suspend (StorageLocationTestHarness) -> Unit) {
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
            val repository = StorageLocationRepositoryI()
            val tx = ExposedTransactionalRunner(database)
            val service = StorageLocationServiceI(storageLocationRepository = repository, tx = tx)

            runBlocking {
                tx.transactional {
                    UserEntity.new {
                        email = "storage-test-user-1@example.com"
                        name = "Storage Test User One"
                        passwordHash = "unused"
                    }
                    UserEntity.new {
                        email = "storage-test-user-2@example.com"
                        name = "Storage Test User Two"
                        passwordHash = "unused"
                    }
                }
                block(StorageLocationTestHarness(repository, service, tx))
            }
        } finally {
            (dataSource as? AutoCloseable)?.close()
        }
    } finally {
        postgres.stop()
    }
}
