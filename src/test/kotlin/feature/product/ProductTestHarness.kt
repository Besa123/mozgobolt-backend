package com.shelflife.feature.product

import com.shelflife.core.database.DatabaseFactory
import com.shelflife.core.database.ExposedTransactionalRunner
import com.shelflife.core.database.TransactionalRunner
import com.shelflife.feature.product.data.repository.ProductRepositoryI
import com.shelflife.feature.product.service.ProductServiceI
import com.shelflife.feature.user.data.database.UserEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

data class ProductTestHarness(
    val repository: ProductRepositoryI,
    val service: ProductServiceI,
    val tx: TransactionalRunner,
)

fun skipIfNoDocker() {
    assumeTrue(
        "Docker is not available locally — skipping (this still runs in CI)",
        DockerClientFactory.instance().isDockerAvailable,
    )
}

/**
 * Runs the V1–V3 migrations (including the real seed data) against a throwaway Postgres, and
 * seeds two `users` rows so private-product tests (which hardcode `userId` 1 and 2) satisfy
 * `products.user_id`'s foreign key — they get ids 1 and 2 respectively, in insertion order on an
 * otherwise-empty `SERIAL` primary key. A real user always exists in production (the id comes off
 * an authenticated JWT); nothing seeds one here automatically.
 */
fun withRealProductDatabase(block: suspend (ProductTestHarness) -> Unit) {
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
            val repository = ProductRepositoryI()
            val tx = ExposedTransactionalRunner(database)
            val service = ProductServiceI(productRepository = repository, tx = tx)

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
                block(ProductTestHarness(repository, service, tx))
            }
        } finally {
            (dataSource as? AutoCloseable)?.close()
        }
    } finally {
        postgres.stop()
    }
}
