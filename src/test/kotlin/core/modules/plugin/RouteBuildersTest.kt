package com.mozgobolt.core.modules.plugin

import com.mozgobolt.core.domain.validation.ValidatedRequest
import io.ktor.client.request.delete
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private data class DummyRequest(
    val value: String = "ok",
) : ValidatedRequest {
    override fun validate() = if (value.isBlank()) listOf("value is required") else emptyList()
}

class RouteBuildersTest {
    @Test
    fun `validatedPut deserializes, validates, and reaches the handler on success`() =
        testApplication {
            application { installValidatedRoute() }

            val response =
                client.put("/put-test") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"value":"hello"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("hello", response.bodyAsText())
        }

    @Test
    fun `validatedPut rejects an invalid body before the handler runs`() =
        testApplication {
            application { installValidatedRoute() }

            val response =
                client.put("/put-test") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"value":""}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `validatedPut enforces the body limit passed to it`() =
        testApplication {
            application { installValidatedRoute() }
            val oversized = "a".repeat(2 * 1024)

            val response =
                client.put("/put-test") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"value":"$oversized"}""")
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }

    @Test
    fun `validatedPatch deserializes, validates, and reaches the handler on success`() =
        testApplication {
            application { installValidatedRoute() }

            val response =
                client.patch("/patch-test") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"value":"hello"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("hello", response.bodyAsText())
        }

    @Test
    fun `validatedPatch rejects an invalid body before the handler runs`() =
        testApplication {
            application { installValidatedRoute() }

            val response =
                client.patch("/patch-test") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"value":""}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `validatedPost deserializes, validates, and reaches the handler on success`() =
        testApplication {
            application { installValidatedRoute() }

            val response =
                client.post("/post-test") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"value":"hello"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("hello", response.bodyAsText())
        }

    @Test
    fun `validatedPost rejects an invalid body before the handler runs`() =
        testApplication {
            application { installValidatedRoute() }

            val response =
                client.post("/post-test") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"value":""}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `validatedPost enforces the body limit passed to it`() =
        testApplication {
            application { installValidatedRoute() }
            val oversized = "a".repeat(2 * 1024)

            val response =
                client.post("/post-test") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"value":"$oversized"}""")
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }

    @Test
    fun `limitedPost reaches the handler when the body is within the limit`() =
        testApplication {
            application { installValidatedRoute() }

            val response = client.post("/limited-post-test") { setBody("ok") }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `limitedPost rejects a body over its limit before the handler runs`() =
        testApplication {
            application { installValidatedRoute() }
            val oversized = "a".repeat(2 * 1024)

            val response = client.post("/limited-post-test") { setBody(oversized) }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }

    @Test
    fun `limitedDelete reaches the handler for a body-less request`() =
        testApplication {
            application { installValidatedRoute() }

            val response = client.delete("/limited-delete-test")

            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `limitedDelete rejects an oversized body before the handler runs`() =
        testApplication {
            application { installValidatedRoute() }
            val oversized = "a".repeat(2 * 1024)

            val response = client.delete("/limited-delete-test") { setBody(oversized) }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }

    private fun Application.installValidatedRoute() {
        install(ContentNegotiation) { json() }
        configureRequestValidation()
        configureStatusPages()
        routing {
            validatedPut<DummyRequest>("/put-test", BodyLimit.TINY) { request ->
                call.respondText(request.value)
            }
            validatedPatch<DummyRequest>("/patch-test", BodyLimit.TINY) { request ->
                call.respondText(request.value)
            }
            validatedPost<DummyRequest>("/post-test", BodyLimit.TINY) { request ->
                call.respondText(request.value)
            }
            limitedPost("/limited-post-test", BodyLimit.TINY) {
                call.respond(HttpStatusCode.OK)
            }
            limitedDelete("/limited-delete-test", BodyLimit.TINY) {
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
