/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.modules.plugin

import com.mozgobolt.core.domain.validation.ValidatedRequest
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Serializable
private data class TimeoutTestRequest(
    val value: String = "ok",
) : ValidatedRequest {
    override fun validate() = emptyList<String>()
}

class RequestTimeoutEnforcementTest {
    @Test
    fun `the global safety net responds with a sanitized gateway timeout`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureStatusPages()
                configureRequestTimeout(50.milliseconds)
                routing {
                    get("/global-slow-test") {
                        delay(1.seconds)
                        call.respondText("too late")
                    }
                }
            }

            val response = client.get("/global-slow-test")

            assertEquals(HttpStatusCode.GatewayTimeout, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("REQUEST_TIMEOUT"))
            assertFalse(body.contains("too late"))
        }

    @Test
    fun `the global timeout allows ordinary requests and explicitly exempted long-lived paths`() =
        testApplication {
            val exemptPath = "/test/long-lived-request"
            exemptFromRequestTimeout(exemptPath)
            application {
                configureRequestTimeout()
                routing {
                    get("/test/ordinary-request") { call.respondText("ok") }
                    get(exemptPath) { call.respondText("event stream setup") }
                }
            }

            val ordinary = client.get("/test/ordinary-request")
            val longLived = client.get(exemptPath)

            assertEquals(HttpStatusCode.OK, ordinary.status)
            assertEquals(HttpStatusCode.OK, longLived.status)
        }

    @Test
    fun `a handler slower than RequestTimeout FAST is cut off at 504, not left to hang`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureStatusPages()
                routing {
                    validatedPost<TimeoutTestRequest>("/slow-test", BodyLimit.TINY, RequestTimeout.FAST) {
                        delay(RequestTimeout.FAST.duration + 1.seconds)
                        call.respond(HttpStatusCode.OK)
                    }
                }
            }

            val response =
                client.post("/slow-test") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"value":"x"}""")
                }

            assertEquals(HttpStatusCode.GatewayTimeout, response.status)
        }
}
