package com.shelflife.core.modules.plugin

import com.shelflife.core.domain.validation.ValidatedRequest
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
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds

@Serializable
private data class AlwaysInvalidRequest(
    val value: String = "",
) : ValidatedRequest {
    override fun validate() = listOf("always invalid")
}

@Serializable
private data class AlwaysValidRequest(
    val value: String = "ok",
) : ValidatedRequest {
    override fun validate() = emptyList<String>()
}

class StatusPagesConfigTest {
    @Test
    fun `a request validation failure returns 400 with the validation reasons`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureRequestValidation()
                configureStatusPages()
                routing {
                    post("/validate-test") {
                        call.receive<AlwaysInvalidRequest>()
                        call.respond(HttpStatusCode.OK)
                    }
                }
            }

            val response =
                client.post("/validate-test") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"value":"x"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(true, response.bodyAsText().contains("VALIDATION_FAILED"))
            assertEquals(true, response.bodyAsText().contains("always invalid"))
        }

    @Test
    fun `a coroutine timeout inside the route returns 504 instead of crashing`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureStatusPages()
                routing {
                    get("/timeout-test") {
                        withTimeout(1.milliseconds) {
                            delay(500.milliseconds)
                        }
                        call.respond(HttpStatusCode.OK)
                    }
                }
            }

            val response = client.get("/timeout-test")

            assertEquals(HttpStatusCode.GatewayTimeout, response.status)
            assertEquals(true, response.bodyAsText().contains("REQUEST_TIMEOUT"))
        }

    @Test
    fun `syntactically invalid json is reported as a malformed request`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureStatusPages()
                routing {
                    post("/malformed-body-test") {
                        call.receive<AlwaysValidRequest>()
                        call.respond(HttpStatusCode.OK)
                    }
                }
            }

            val response =
                client.post("/malformed-body-test") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"value": this is not valid json""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(true, response.bodyAsText().contains("MALFORMED_REQUEST"))
        }

    @Test
    fun `a body sent with a content type nothing can convert is reported as an invalid body`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureStatusPages()
                routing {
                    post("/invalid-body-test") {
                        call.receive<AlwaysValidRequest>()
                        call.respond(HttpStatusCode.OK)
                    }
                }
            }

            val response =
                client.post("/invalid-body-test") {
                    contentType(ContentType.Text.Plain)
                    setBody("""{"value":"ok"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(true, response.bodyAsText().contains("INVALID_BODY"))
        }

    @Test
    fun `an unexpected exception returns a generic 500 without leaking internal details`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureStatusPages()
                routing {
                    get("/boom-test") {
                        error("db password is Str0ngPass1, connection string leaked here")
                    }
                }
            }

            val response = client.get("/boom-test")

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val body = response.bodyAsText()
            assertEquals(true, body.contains("INTERNAL_SERVER_ERROR"))
            assertFalse(body.contains("password"), "the response must not leak the exception message: $body")
            assertFalse(body.contains("Str0ngPass1"), "the response must not leak the exception message: $body")
        }
}
