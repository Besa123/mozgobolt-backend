package com.shelflife.core.modules.plugin

import com.shelflife.core.configureTestEnvironment
import com.shelflife.core.installTestModules
import com.shelflife.core.testAccessTokenFor
import com.shelflife.core.utility.functions.protectedApi
import com.shelflife.core.utility.functions.publicRateLimitedApi
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ktor.server.routing.post as routePost

class RateLimitConfigTest {
    @Test
    fun `API_LIMIT gives each authenticated user their own independent budget`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                routing {
                    protectedApi {
                        get("/api-limit-test") { call.respond(HttpStatusCode.OK) }
                    }
                }
            }
            val userAToken = testAccessTokenFor(userId = 1)
            val userBToken = testAccessTokenFor(userId = 2)

            val userAStatuses =
                (1..61).map { client.get("/api-limit-test") { bearerAuth(userAToken) }.status }
            val userBFirstRequest = client.get("/api-limit-test") { bearerAuth(userBToken) }.status

            assertEquals(listOf(HttpStatusCode.OK), userAStatuses.take(60).distinct())
            assertEquals(HttpStatusCode.TooManyRequests, userAStatuses.last())
            assertEquals(HttpStatusCode.OK, userBFirstRequest, "a different user must have their own fresh budget")
        }

    @Test
    fun `API_LIMIT falls back to per-IP keying when there is no JWT principal`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                routing {
                    rateLimit(RateLimitName(API_LIMIT)) {
                        get("/api-limit-no-auth-test") { call.respond(HttpStatusCode.OK) }
                    }
                }
            }

            val statuses = (1..61).map { client.get("/api-limit-no-auth-test").status }

            assertEquals(listOf(HttpStatusCode.OK), statuses.take(60).distinct())
            assertEquals(HttpStatusCode.TooManyRequests, statuses.last())
        }

    @Test
    fun `UPLOAD_LIMIT rejects the eleventh request within the window`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                routing {
                    rateLimit(RateLimitName(UPLOAD_LIMIT)) {
                        routePost("/upload-limit-test") { call.respond(HttpStatusCode.OK) }
                    }
                }
            }

            val statuses = (1..11).map { client.post("/upload-limit-test").status }

            assertEquals(listOf(HttpStatusCode.OK), statuses.take(10).distinct())
            assertEquals(HttpStatusCode.TooManyRequests, statuses.last())
        }

    @Test
    fun `AUTH_LIMIT rejects the sixth request within the window`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                routing {
                    publicRateLimitedApi {
                        routePost("/auth-limit-test") { call.respond(HttpStatusCode.OK) }
                    }
                }
            }

            val statuses = (1..6).map { client.post("/auth-limit-test").status }

            assertEquals(listOf(HttpStatusCode.OK), statuses.take(5).distinct())
            assertEquals(HttpStatusCode.TooManyRequests, statuses.last())
        }

    @Test
    fun `an unauthenticated request to protectedApi is rejected without consuming the rate-limit budget`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                routing {
                    protectedApi {
                        get("/protected-no-auth-test") { call.respond(HttpStatusCode.OK) }
                    }
                }
            }

            // Well past the API_LIMIT (60/min) budget — if auth ran after/inside the limiter,
            // some of these would eventually flip from 401 to 429 instead of staying 401 throughout.
            val statuses = (1..65).map { client.get("/protected-no-auth-test").status }

            assertEquals(listOf(HttpStatusCode.Unauthorized), statuses.distinct())
        }

    @Test
    fun `the global limit applies even to a route with no named limiter`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                routing {
                    get("/no-named-limit-test") { call.respond(HttpStatusCode.OK) }
                }
            }

            val statuses = (1..151).map { client.get("/no-named-limit-test").status }

            assertEquals(listOf(HttpStatusCode.OK), statuses.take(150).distinct())
            assertEquals(HttpStatusCode.TooManyRequests, statuses.last())
        }
}
