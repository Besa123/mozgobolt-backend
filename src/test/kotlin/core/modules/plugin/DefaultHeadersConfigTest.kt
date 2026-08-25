package com.shelflife.core.modules.plugin

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultHeadersConfigTest {
    @Test
    fun `security headers are present on every response`() =
        testApplication {
            application {
                configureDefaultHeaders()
                routing { get("/headers-test") { call.respond(HttpStatusCode.OK) } }
            }

            val response = client.get("/headers-test")

            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals("DENY", response.headers["X-Frame-Options"])
            assertEquals("0", response.headers["X-XSS-Protection"])
            assertEquals("strict-origin-when-cross-origin", response.headers["Referrer-Policy"])
            assertEquals("max-age=63072000; includeSubDomains; preload", response.headers["Strict-Transport-Security"])
            assertEquals(
                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
                response.headers["Content-Security-Policy"],
            )
            assertEquals("same-origin", response.headers["Cross-Origin-Opener-Policy"])
            assertEquals("no-store", response.headers["Cache-Control"])
            assertEquals("none", response.headers["X-Permitted-Cross-Domain-Policies"])
        }

    @Test
    fun `the Server and X-Powered-By headers are blanked out, not left at their defaults`() =
        testApplication {
            application {
                configureDefaultHeaders()
                routing { get("/headers-test") { call.respond(HttpStatusCode.OK) } }
            }

            val response = client.get("/headers-test")

            assertEquals("", response.headers["X-Powered-By"] ?: "")
            assertEquals("", response.headers["Server"] ?: "")
        }
}
