package com.besa.shelflife.feature.user.routing

import com.besa.shelflife.core.configureTestEnvironment
import com.besa.shelflife.core.domain.AppResult
import com.besa.shelflife.feature.user.domain.model.RegisterError
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class RegisterRouteTest {
    @Test
    fun `a well-formed registration returns 201 with an empty body`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val response = postJson(AuthPaths.REGISTER, VALID_BODY)

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals("", response.bodyAsText())
            assertEquals("user@example.com", userService.lastCreateUserCall?.email)
        }

    @Test
    fun `a duplicate email returns 409`() =
        testApplication {
            configureTestEnvironment()
            val userService =
                FakeUserService().apply {
                    createUserResult =
                        AppResult.Error(
                            RegisterError.ALREADY_EXISTS,
                        )
                }
            application { installAuthRoutesTestApp(userService) }

            val response = postJson(AuthPaths.REGISTER, VALID_BODY)

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("ALREADY_EXISTS", response.errorBody().error)
        }

    @Test
    fun `a weak password returns 400`() =
        testApplication {
            configureTestEnvironment()
            val userService =
                FakeUserService().apply {
                    createUserResult =
                        AppResult.Error(
                            RegisterError.WEAK_PASSWORD,
                        )
                }
            application { installAuthRoutesTestApp(userService) }

            val response =
                postJson(AuthPaths.REGISTER, """{"password":"weak","email":"user@example.com","name":"User"}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("WEAK_PASSWORD", response.errorBody().error)
        }

    @Test
    fun `an invalid email returns 400`() =
        testApplication {
            configureTestEnvironment()
            val userService =
                FakeUserService().apply {
                    createUserResult =
                        AppResult.Error(
                            RegisterError.INVALID_EMAIL,
                        )
                }
            application { installAuthRoutesTestApp(userService) }

            val response =
                postJson(AuthPaths.REGISTER, """{"password":"Str0ngPass1","email":"not-an-email","name":"User"}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("INVALID_EMAIL", response.errorBody().error)
        }

    @Test
    fun `a blank field fails DTO validation before the service is even called`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val response = postJson(AuthPaths.REGISTER, """{"password":"","email":"user@example.com","name":"User"}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_FAILED", response.errorBody().error)
            assertEquals(null, userService.lastCreateUserCall, "the service must not be called for an invalid request")
        }

    @Test
    fun `a name with markup-like characters fails validation`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val response =
                postJson(
                    AuthPaths.REGISTER,
                    """{"password":"Str0ngPass1","email":"user@example.com","name":"<script>x</script>"}""",
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_FAILED", response.errorBody().error)
        }

    @Test
    fun `syntactically invalid json returns 400 instead of a 500`() =
        testApplication {
            configureTestEnvironment()
            application { installAuthRoutesTestApp(FakeUserService()) }

            val response = postJson(AuthPaths.REGISTER, """{"password": this is not valid json""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("MALFORMED_REQUEST", response.errorBody().error)
        }

    @Test
    fun `a body sent with the wrong content type returns 400 instead of a 500`() =
        testApplication {
            configureTestEnvironment()
            application { installAuthRoutesTestApp(FakeUserService()) }

            val response =
                client.post(AuthPaths.REGISTER) {
                    contentType(ContentType.Text.Plain)
                    setBody(VALID_BODY)
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("INVALID_BODY", response.errorBody().error)
        }

    @Test
    fun `a request with no body at all returns 400 instead of a 500`() =
        testApplication {
            configureTestEnvironment()
            application { installAuthRoutesTestApp(FakeUserService()) }

            val response = client.post(AuthPaths.REGISTER) { contentType(ContentType.Application.Json) }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("MALFORMED_REQUEST", response.errorBody().error)
        }

    @Test
    fun `a body over the 1KB register limit is rejected with 413`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }
            val oversizedName = "a".repeat(2 * 1024)

            val response =
                postJson(
                    AuthPaths.REGISTER,
                    """{"password":"Str0ngPass1","email":"user@example.com","name":"$oversizedName"}""",
                )

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals(null, userService.lastCreateUserCall)
        }

    @Test
    fun `registering twice with the same idempotency key does not call the service twice`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val first = postJson(AuthPaths.REGISTER, VALID_BODY) { header("Idempotency-Key", "reg-key-1") }
            val second = postJson(AuthPaths.REGISTER, VALID_BODY) { header("Idempotency-Key", "reg-key-1") }

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(HttpStatusCode.Created, second.status)
            assertEquals("true", second.headers["X-Idempotency-Replayed"])
            assertEquals(1, userService.createUserCallCount, "the handler must not run a second time on replay")
        }

    @Test
    fun `reusing the same idempotency key with a different email is rejected`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            postJson(AuthPaths.REGISTER, """{"password":"Str0ngPass1","email":"first@example.com","name":"User"}""") {
                header("Idempotency-Key", "reg-key-1")
            }
            val second =
                postJson(
                    AuthPaths.REGISTER,
                    """{"password":"Str0ngPass1","email":"second@example.com","name":"User"}""",
                ) {
                    header("Idempotency-Key", "reg-key-1")
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, second.status)
        }

    private companion object {
        const val VALID_BODY = """{"password":"Str0ngPass1","email":"user@example.com","name":"User"}"""
    }
}
