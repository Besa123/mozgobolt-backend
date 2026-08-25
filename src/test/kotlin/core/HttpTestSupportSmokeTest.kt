package com.shelflife.core

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpTestSupportSmokeTest {
    @Test
    fun `the test module stack installs and serves a trivial route`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                routing {
                    get("/smoke-test") { call.respond(HttpStatusCode.OK) }
                }
            }

            val response = client.get("/smoke-test")

            assertEquals(HttpStatusCode.OK, response.status)
        }
}
