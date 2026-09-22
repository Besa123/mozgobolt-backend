package com.mozgobolt.core.data.idempotency

import com.mozgobolt.core.modules.plugin.configureContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdempotencyHandlerTest {
    @Test
    fun `without an idempotency key header the handler just runs normally`() =
        testApplication {
            val store = FakeIdempotencyStore()
            val invocations = AtomicInteger(0)
            application { installIdempotentTestRoute(store, invocations) }

            val response = client.post("/idempotent-test") { header("X-Fingerprint", "a") }

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(1, invocations.get())
            assertNull(response.headers["X-Idempotency-Replayed"])
        }

    @Test
    fun `a fresh idempotency key runs the handler once and stores the result`() =
        testApplication {
            val store = FakeIdempotencyStore()
            val invocations = AtomicInteger(0)
            application { installIdempotentTestRoute(store, invocations) }

            val response =
                client.post("/idempotent-test") {
                    header(IDEMPOTENCY_KEY_HEADER, "key-1")
                    header("X-Fingerprint", "a")
                }

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(1, invocations.get())
            assertEquals(201, store.find("key-1")?.statusCode)
        }

    @Test
    fun `replaying the same key and fingerprint returns the stored response without re-running the handler`() =
        testApplication {
            val store = FakeIdempotencyStore()
            val invocations = AtomicInteger(0)
            application { installIdempotentTestRoute(store, invocations) }

            client.post("/idempotent-test") {
                header(IDEMPOTENCY_KEY_HEADER, "key-1")
                header("X-Fingerprint", "a")
            }
            val replay =
                client.post("/idempotent-test") {
                    header(IDEMPOTENCY_KEY_HEADER, "key-1")
                    header("X-Fingerprint", "a")
                }

            assertEquals(HttpStatusCode.Created, replay.status)
            assertEquals(1, invocations.get(), "handler must not run a second time on replay")
            assertEquals("true", replay.headers["X-Idempotency-Replayed"])
            assertEquals("""{"call":1}""", replay.bodyAsText())
        }

    @Test
    fun `reusing the same key with a different payload fingerprint is rejected`() =
        testApplication {
            val store = FakeIdempotencyStore()
            val invocations = AtomicInteger(0)
            application { installIdempotentTestRoute(store, invocations) }

            client.post("/idempotent-test") {
                header(IDEMPOTENCY_KEY_HEADER, "key-1")
                header("X-Fingerprint", "a")
            }
            val conflicting =
                client.post("/idempotent-test") {
                    header(IDEMPOTENCY_KEY_HEADER, "key-1")
                    header("X-Fingerprint", "b")
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, conflicting.status)
            assertEquals(1, invocations.get(), "handler must not run for a rejected key reuse")
        }

    @Test
    fun `a key that is still locked and not stale is rejected as in-progress`() =
        testApplication {
            val store = FakeIdempotencyStore()
            val invocations = AtomicInteger(0)
            application { installIdempotentTestRoute(store, invocations) }
            store.seed(inProgressRecord(fingerprint = "a", createdAt = Instant.now()))

            val response =
                client.post("/idempotent-test") {
                    header(IDEMPOTENCY_KEY_HEADER, "key-1")
                    header("X-Fingerprint", "a")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(0, invocations.get())
        }

    @Test
    fun `a stale lock left behind by a crashed request is cleared and retried`() =
        testApplication {
            val store = FakeIdempotencyStore()
            val invocations = AtomicInteger(0)
            application { installIdempotentTestRoute(store, invocations) }
            store.seed(inProgressRecord(fingerprint = "a", createdAt = Instant.now().minusSeconds(600)))

            val response =
                client.post("/idempotent-test") {
                    header(IDEMPOTENCY_KEY_HEADER, "key-1")
                    header("X-Fingerprint", "a")
                }

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(1, invocations.get(), "a stale lock should let the handler run")
        }

    @Test
    fun `losing the race to acquire the lock is rejected as in-progress, not run twice`() =
        testApplication {
            val invocations = AtomicInteger(0)
            val raceLosingStore =
                object : IdempotencyStore {
                    override suspend fun find(key: String): IdempotencyRecord? = null

                    override suspend fun acquireLock(
                        key: String,
                        fingerprint: String,
                    ): Boolean = false

                    override suspend fun complete(
                        key: String,
                        statusCode: Int,
                        body: String,
                    ) = Unit

                    override suspend fun deleteStaleLock(key: String) = Unit
                }
            application { installIdempotentTestRoute(raceLosingStore, invocations) }

            val response =
                client.post("/idempotent-test") {
                    header(IDEMPOTENCY_KEY_HEADER, "key-1")
                    header("X-Fingerprint", "a")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(0, invocations.get(), "the handler must not run if the lock could not be acquired")
        }

    @Test
    fun `an idempotency key with invalid characters is rejected before the handler runs`() =
        testApplication {
            val store = FakeIdempotencyStore()
            val invocations = AtomicInteger(0)
            application { installIdempotentTestRoute(store, invocations) }

            val response =
                client.post("/idempotent-test") {
                    header(IDEMPOTENCY_KEY_HEADER, "not a valid key!!")
                    header("X-Fingerprint", "a")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, invocations.get())
        }

    @Test
    fun `an idempotency key over the maximum length is rejected before the handler runs`() =
        testApplication {
            val store = FakeIdempotencyStore()
            val invocations = AtomicInteger(0)
            application { installIdempotentTestRoute(store, invocations) }

            val response =
                client.post("/idempotent-test") {
                    header(IDEMPOTENCY_KEY_HEADER, "a".repeat(65))
                    header("X-Fingerprint", "a")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, invocations.get())
        }

    @Test
    fun `an empty request fingerprint skips the payload-match check entirely`() {
        testApplication {
            val store = FakeIdempotencyStore()
            val invocations = AtomicInteger(0)
            application { installIdempotentTestRoute(store, invocations, fingerprintFromHeader = false) }

            client.post("/idempotent-test") { header(IDEMPOTENCY_KEY_HEADER, "key-1") }
            val second = client.post("/idempotent-test") { header(IDEMPOTENCY_KEY_HEADER, "key-1") }

            assertEquals(HttpStatusCode.Created, second.status)
            assertEquals(1, invocations.get())
        }
    }

    private fun inProgressRecord(
        fingerprint: String,
        createdAt: Instant,
    ) = IdempotencyRecord(
        key = "key-1",
        requestFingerprint = sha256Hex(fingerprint),
        statusCode = null,
        responseBody = "",
        createdAt = createdAt,
    )

    private fun sha256Hex(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun Application.installIdempotentTestRoute(
        store: IdempotencyStore,
        invocations: AtomicInteger,
        fingerprintFromHeader: Boolean = true,
    ) {
        configureContentNegotiation()
        routing {
            post("/idempotent-test") {
                val fingerprint = if (fingerprintFromHeader) call.request.headers["X-Fingerprint"] ?: "" else ""
                idempotent(store, requestFingerprint = fingerprint) {
                    idempotentResult(HttpStatusCode.Created, mapOf("call" to invocations.incrementAndGet()))
                }
            }
        }
    }
}
