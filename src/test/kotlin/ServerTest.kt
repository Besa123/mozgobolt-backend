package com.besa.shelflife

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerTest {
    @Test
    fun `test root endpoint`() =
        testApplication {
            assumeTrue(
                "DB_URL is not set — skipping (requires the env vars in .env.example plus a running Postgres)",
                System.getenv("DB_URL") != null,
            )

            application {
                rootModule()
            }
            // verify server root returns 200
            assertEquals(HttpStatusCode.OK, client.get("/").status)
        }
}
