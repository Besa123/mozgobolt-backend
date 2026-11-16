/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.modules.plugin

import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.installTestModules
import com.mozgobolt.core.testAccessTokenFor
import com.mozgobolt.core.utility.functions.protectedApi
import com.mozgobolt.core.utility.functions.publicRateLimitedApi
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

            val userAResponses =
                (1..61).map { client.get("/api-limit-test") { bearerAuth(userAToken) } }
            val userBFirstRequest = client.get("/api-limit-test") { bearerAuth(userBToken) }

            assertEquals(listOf(HttpStatusCode.OK), userAResponses.take(60).map { it.status }.distinct())
            val rateLimited = userAResponses.last()
            assertEquals(HttpStatusCode.TooManyRequests, rateLimited.status)
            assertTrue(rateLimited.bodyAsText().contains("RATE_LIMITED"))
            assertTrue(rateLimited.headers[HttpHeaders.RetryAfter] != null)
            assertEquals(
                HttpStatusCode.OK,
                userBFirstRequest.status,
                "a different user must have their own fresh budget",
            )
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
    fun `UPLOAD_LIMIT gives each forwarded client IP an independent budget`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                routing {
                    rateLimit(RateLimitName(UPLOAD_LIMIT)) {
                        routePost("/upload-limit-per-ip-test") { call.respond(HttpStatusCode.OK) }
                    }
                }
            }

            val firstIpStatuses =
                (1..11).map {
                    client
                        .post("/upload-limit-per-ip-test") {
                            header("X-Forwarded-For", "198.51.100.20, 127.0.0.1")
                        }.status
                }
            val secondIpStatus =
                client
                    .post("/upload-limit-per-ip-test") {
                        header("X-Forwarded-For", "198.51.100.21, 127.0.0.1")
                    }.status

            assertEquals(10, firstIpStatuses.count { it == HttpStatusCode.OK })
            assertEquals(HttpStatusCode.TooManyRequests, firstIpStatuses.last())
            assertEquals(HttpStatusCode.OK, secondIpStatus)
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
    fun `AUTH_LIMIT does not oversubscribe during a concurrent burst from one source`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                routing {
                    publicRateLimitedApi {
                        routePost("/auth-limit-burst-test") { call.respond(HttpStatusCode.OK) }
                    }
                }
            }

            val statuses =
                coroutineScope {
                    (1..1_000)
                        .map {
                            async(Dispatchers.IO) {
                                client.post("/auth-limit-burst-test").status
                            }
                        }.awaitAll()
                }

            assertEquals(5, statuses.count { it == HttpStatusCode.OK })
            assertEquals(995, statuses.count { it == HttpStatusCode.TooManyRequests })
        }

    @Test
    fun `AUTH_LIMIT gives each forwarded client IP an independent budget`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                routing {
                    publicRateLimitedApi {
                        routePost("/auth-limit-per-ip-test") { call.respond(HttpStatusCode.OK) }
                    }
                }
            }

            val firstIpStatuses =
                (1..6).map {
                    client
                        .post("/auth-limit-per-ip-test") {
                            header("X-Forwarded-For", "198.51.100.1, 127.0.0.1")
                        }.status
                }
            val secondIpStatus =
                client
                    .post("/auth-limit-per-ip-test") {
                        header("X-Forwarded-For", "198.51.100.2, 127.0.0.1")
                    }.status

            assertEquals(5, firstIpStatuses.count { it == HttpStatusCode.OK })
            assertEquals(HttpStatusCode.TooManyRequests, firstIpStatuses.last())
            assertEquals(HttpStatusCode.OK, secondIpStatus)
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

    @Test
    fun `the global limit has an independent budget for each forwarded client IP`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                routing {
                    get("/global-per-ip-test") { call.respond(HttpStatusCode.OK) }
                }
            }

            val firstIpStatuses =
                (1..151).map {
                    client
                        .get("/global-per-ip-test") {
                            header("X-Forwarded-For", "198.51.100.10, 127.0.0.1")
                        }.status
                }
            val secondIpStatus =
                client
                    .get("/global-per-ip-test") {
                        header("X-Forwarded-For", "198.51.100.11, 127.0.0.1")
                    }.status

            assertEquals(150, firstIpStatuses.count { it == HttpStatusCode.OK })
            assertEquals(HttpStatusCode.TooManyRequests, firstIpStatuses.last())
            assertEquals(HttpStatusCode.OK, secondIpStatus)
        }
}
