package com.besa.shelflife.core.modules.plugin

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CorsConfigTest {
    @Test
    fun `an allowed origin gets Access-Control-Allow-Origin and credentials on a simple request`() =
        testApplication {
            environment {
                config =
                    MapApplicationConfig(
                        "app.cors.allowedHosts" to "allowed.example.com",
                        "ktor.environment" to "production",
                    )
            }
            application { installCorsTestRoute() }

            val response = client.get("/cors-test") { header(HttpHeaders.Origin, "https://allowed.example.com") }

            assertEquals("https://allowed.example.com", response.headers[HttpHeaders.AccessControlAllowOrigin])
            assertEquals("true", response.headers[HttpHeaders.AccessControlAllowCredentials])
        }

    @Test
    fun `a disallowed origin does not get an Access-Control-Allow-Origin header`() =
        testApplication {
            environment {
                config =
                    MapApplicationConfig(
                        "app.cors.allowedHosts" to "allowed.example.com",
                        "ktor.environment" to "production",
                    )
            }
            application { installCorsTestRoute() }

            val response = client.get("/cors-test") { header(HttpHeaders.Origin, "https://evil.example.com") }

            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    @Test
    fun `a disallowed origin preflight request is rejected`() =
        testApplication {
            environment {
                config =
                    MapApplicationConfig(
                        "app.cors.allowedHosts" to "allowed.example.com",
                        "ktor.environment" to "production",
                    )
            }
            application { installCorsTestRoute() }

            val response =
                client.options("/cors-test") {
                    header(HttpHeaders.Origin, "https://evil.example.com")
                    header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Get.value)
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `localhost dev origins are allowed outside production even when not configured`() =
        testApplication {
            environment {
                config =
                    MapApplicationConfig(
                        "app.cors.allowedHosts" to "",
                        "ktor.environment" to "local",
                    )
            }
            application { installCorsTestRoute() }

            val response = client.get("/cors-test") { header(HttpHeaders.Origin, "http://localhost:3000") }

            assertEquals("http://localhost:3000", response.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    @Test
    fun `localhost dev origins are not allowed in production`() =
        testApplication {
            environment {
                config =
                    MapApplicationConfig(
                        "app.cors.allowedHosts" to "",
                        "ktor.environment" to "production",
                    )
            }
            application { installCorsTestRoute() }

            val response = client.get("/cors-test") { header(HttpHeaders.Origin, "http://localhost:3000") }

            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    private fun Application.installCorsTestRoute() {
        configureCors()
        routing {
            get("/cors-test") { call.respond(HttpStatusCode.OK) }
        }
    }
}
