package com.shelflife.feature.product

import com.shelflife.core.database.DatabaseFactory
import com.shelflife.core.database.ExposedTransactionalRunner
import com.shelflife.core.database.TransactionalRunner
import com.shelflife.feature.product.data.repository.ProductRepositoryI
import com.shelflife.feature.product.service.ProductServiceI
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

/** Runs the V1–V3 migrations (including the real seed data) against a throwaway Postgres. */
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

            runBlocking { block(ProductTestHarness(repository, service, tx)) }
        } finally {
            (dataSource as? AutoCloseable)?.close()
        }
    } finally {
        postgres.stop()
    }
}
