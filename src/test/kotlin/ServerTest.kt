package com.mozgobolt

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.testing.testApplication
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

private val REQUIRED_ENV_VARS =
    listOf("DB_URL", "DB_USER", "DB_PASSWORD", "JWT_SECRET", "JWT_ISSUER", "JWT_AUDIENCE", "PASSWORD_PEPPER")

class ServerTest {
    @Test
    fun `the app boots for real and reports healthy`() =
        testApplication {
            val missing = REQUIRED_ENV_VARS.filter { System.getenv(it) == null }
            assumeTrue(
                "Missing env vars $missing — skipping (requires everything in .env.example plus a running Postgres)",
                missing.isEmpty(),
            )

            environment {
                config = ApplicationConfig("application.conf")
            }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
        }
}
