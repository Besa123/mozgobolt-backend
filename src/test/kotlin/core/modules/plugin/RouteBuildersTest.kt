/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.modules.plugin

import com.mozgobolt.core.domain.validation.ValidatedRequest
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private data class DummyRequest(
    val value: String = "ok",
) : ValidatedRequest {
    override fun validate() = if (value.isBlank()) listOf("value is required") else emptyList()
}

class RouteBuildersTest {
    @Test
    fun `validated helpers use their default body limits and timeouts`() =
        testApplication {
            application { installValidatedRoute() }
            val requestBody = """{"value":"default options"}"""

            val post =
                client.post("/default-post-test") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
            val put =
                client.put("/default-put-test") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
            val patch =
                client.patch("/default-patch-test") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

            assertEquals(HttpStatusCode.OK, post.status)
            assertEquals(HttpStatusCode.OK, put.status)
            assertEquals(HttpStatusCode.OK, patch.status)
        }

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
    fun `chunked JSON bodies are bounded by the route limit`() =
        testApplication {
            application { installValidatedRoute() }

            val withinLimit =
                client.post("/post-test") {
                    setBody(ChunkedJsonContent(jsonBodyOfSize(BodyLimit.TINY.bytes.toInt())))
                }
            val overLimit =
                client.post("/post-test") {
                    setBody(ChunkedJsonContent(jsonBodyOfSize(BodyLimit.TINY.bytes.toInt() + 1)))
                }

            assertEquals(HttpStatusCode.OK, withinLimit.status)
            assertEquals(HttpStatusCode.PayloadTooLarge, overLimit.status)
            assertTrue(overLimit.bodyAsText().contains("PAYLOAD_TOO_LARGE"))
        }

    @Test
    fun `chunked bodies on routes without a specialized builder are bounded globally`() =
        testApplication {
            application { installValidatedRoute() }

            val response =
                client.post("/global-body-limit-test") {
                    setBody(ChunkedBinaryContent(ByteArray(BodyLimit.GLOBAL.bytes.toInt() + 1)))
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertTrue(response.bodyAsText().contains("PAYLOAD_TOO_LARGE"))
        }

    @Test
    fun `the same path can have different body-limited HTTP methods`() =
        testApplication {
            application { installValidatedRoute() }

            val post = client.post("/same-body-limit-path")
            val delete = client.delete("/same-body-limit-path")

            assertEquals(HttpStatusCode.OK, post.status)
            assertEquals(HttpStatusCode.NoContent, delete.status)
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

    @Test
    fun `limitedFileUploadPost forwards a bounded file part to the handler`() =
        testApplication {
            application { installValidatedRoute() }

            val response =
                client.submitFormWithBinaryData(
                    url = "/file-post-test",
                    formData =
                        formData {
                            append(
                                "file",
                                byteArrayOf(1, 2, 3),
                                Headers.build { append(HttpHeaders.ContentDisposition, "filename=test.jpg") },
                            )
                        },
                )

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("3", response.bodyAsText())
        }

    @Test
    fun `limitedFileUploadPut accepts a bounded file part`() =
        testApplication {
            application { installValidatedRoute() }

            val response =
                client.put("/file-put-test") {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append(
                                    "file",
                                    byteArrayOf(1, 2),
                                    Headers.build { append(HttpHeaders.ContentDisposition, "filename=test.jpg") },
                                )
                            },
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("2", response.bodyAsText())
        }

    @Test
    fun `file upload helpers reject a missing file part and an oversized request`() =
        testApplication {
            application { installValidatedRoute() }

            val missing =
                client.submitFormWithBinaryData(
                    url = "/file-post-test",
                    formData = formData { append("not-a-file", "text") },
                )
            val oversized =
                client.submitFormWithBinaryData(
                    url = "/file-post-test",
                    formData =
                        formData {
                            append(
                                "file",
                                ByteArray(BodyLimit.TINY.bytes.toInt() + 1),
                                Headers.build { append(HttpHeaders.ContentDisposition, "filename=large.jpg") },
                            )
                        },
                )
            val oversizedPut =
                client.put("/file-put-test") {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append(
                                    "file",
                                    ByteArray(BodyLimit.TINY.bytes.toInt() + 1),
                                    Headers.build { append(HttpHeaders.ContentDisposition, "filename=large.jpg") },
                                )
                            },
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, missing.status)
            assertTrue(missing.bodyAsText().contains("MISSING_FILE"))
            assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
            assertEquals(HttpStatusCode.PayloadTooLarge, oversizedPut.status)
        }

    @Test
    fun `chunked upload enforces the cumulative file byte limit`() =
        testApplication {
            application { installValidatedRoute() }
            val content =
                ChunkedMultipartContent(
                    listOf(
                        ByteArray(600) { 'a'.code.toByte() },
                        ByteArray(600) { 'b'.code.toByte() },
                    ),
                )

            val lengthCheck =
                client.post("/content-length-check") {
                    setBody(content)
                }
            val response =
                client.post("/file-post-test") {
                    setBody(content)
                }

            assertEquals("none", lengthCheck.bodyAsText())
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }

    @Test
    fun `chunked upload counts text fields toward the body limit`() =
        testApplication {
            application { installValidatedRoute() }
            val content =
                ChunkedMultipartContent(
                    files = listOf(byteArrayOf(1)),
                    fields = listOf(ByteArray(BodyLimit.TINY.bytes.toInt()) { 'x'.code.toByte() }),
                )

            val response =
                client.post("/file-post-test") {
                    setBody(content)
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }

    @Test
    fun `a single-file upload rejects multiple file parts`() =
        testApplication {
            application { installValidatedRoute() }
            val content =
                ChunkedMultipartContent(
                    files = listOf(byteArrayOf(1), byteArrayOf(2)),
                )

            val response =
                client.post("/file-post-test") {
                    setBody(content)
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("MULTIPLE_FILES"))
        }

    private fun Application.installValidatedRoute() {
        install(ContentNegotiation) { json() }
        configureRequestValidation()
        configureStatusPages()
        routing {
            installGlobalRequestBodyLimit()
            validatedPut<DummyRequest>("/put-test", BodyLimit.TINY) { request ->
                call.respondText(request.value)
            }
            validatedPatch<DummyRequest>("/patch-test", BodyLimit.TINY) { request ->
                call.respondText(request.value)
            }
            validatedPost<DummyRequest>("/post-test", BodyLimit.TINY) { request ->
                call.respondText(request.value)
            }
            validatedPost<DummyRequest>("/default-post-test") { request ->
                call.respondText(request.value)
            }
            validatedPut<DummyRequest>("/default-put-test") { request ->
                call.respondText(request.value)
            }
            validatedPatch<DummyRequest>("/default-patch-test") { request ->
                call.respondText(request.value)
            }
            limitedPost("/limited-post-test", BodyLimit.TINY) {
                call.respond(HttpStatusCode.OK)
            }
            limitedDelete("/limited-delete-test", BodyLimit.TINY) {
                call.respond(HttpStatusCode.NoContent)
            }
            limitedPost("/same-body-limit-path", BodyLimit.TINY) {
                call.respond(HttpStatusCode.OK)
            }
            limitedDelete("/same-body-limit-path", BodyLimit.TINY) {
                call.respond(HttpStatusCode.NoContent)
            }
            limitedFileUploadPost("/file-post-test", BodyLimit.TINY) { bytes ->
                call.respondText(bytes.size.toString())
            }
            limitedFileUploadPut("/file-put-test", BodyLimit.TINY) { bytes ->
                call.respondText(bytes.size.toString())
            }
            post("/content-length-check") {
                call.respondText(call.request.contentLength()?.toString() ?: "none")
            }
            post("/global-body-limit-test") {
                call.receive<ByteArray>()
                call.respond(HttpStatusCode.OK)
            }
        }
    }

    private fun jsonBodyOfSize(size: Int): String {
        val prefix = """{"value":""" + "\""
        val suffix = "\"}"
        return prefix + "x".repeat(size - prefix.length - suffix.length) + suffix
    }

    private class ChunkedJsonContent(
        private val body: String,
    ) : OutgoingContent.ReadChannelContent() {
        override val contentType = ContentType.Application.Json
        override val contentLength: Long? = null

        override fun readFrom() = ByteReadChannel(body)
    }

    private class ChunkedBinaryContent(
        private val body: ByteArray,
    ) : OutgoingContent.ReadChannelContent() {
        override val contentType = ContentType.Application.OctetStream
        override val contentLength: Long? = null

        override fun readFrom() = ByteReadChannel(body)
    }

    private class ChunkedMultipartContent(
        files: List<ByteArray>,
        fields: List<ByteArray> = emptyList(),
    ) : OutgoingContent.ReadChannelContent() {
        private val boundary = "route-builder-test-boundary"
        private val body =
            ByteArrayOutputStream()
                .apply {
                    fields.forEachIndexed { index, bytes ->
                        writeCrlfLine("--$boundary")
                        writeCrlfLine("""Content-Disposition: form-data; name="field-$index"""")
                        writeCrlfLine("Content-Type: text/plain; charset=UTF-8")
                        write('\r'.code)
                        write('\n'.code)
                        write(bytes)
                        write('\r'.code)
                        write('\n'.code)
                    }
                    files.forEachIndexed { index, bytes ->
                        writeCrlfLine("--$boundary")
                        writeCrlfLine("""Content-Disposition: form-data; name="file"; filename="file-$index.jpg"""")
                        writeCrlfLine("Content-Type: application/octet-stream")
                        write('\r'.code)
                        write('\n'.code)
                        write(bytes)
                        write('\r'.code)
                        write('\n'.code)
                    }
                    writeCrlfLine("--$boundary--")
                }.toByteArray()

        override val contentType = ContentType.MultiPart.FormData.withParameter("boundary", boundary)
        override val contentLength: Long? = null

        override fun readFrom() = ByteReadChannel(body)

        private fun ByteArrayOutputStream.writeCrlfLine(value: String) {
            write(value.toByteArray(StandardCharsets.UTF_8))
            write('\r'.code)
            write('\n'.code)
        }
    }
}
