package com.besa.shelflife.core.modules.plugin

import com.besa.shelflife.core.domain.validation.ValidatedRequest
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@Serializable
private data class TimeoutTestRequest(
    val value: String = "ok",
) : ValidatedRequest {
    override fun validate() = emptyList<String>()
}

class RequestTimeoutEnforcementTest {
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
