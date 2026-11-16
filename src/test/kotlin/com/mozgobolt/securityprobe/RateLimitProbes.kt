/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.securityprobe

import com.mozgobolt.loadtest.forwardedFor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

private const val HTTP_OK = 200
private const val HTTP_TOO_MANY_REQUESTS = 429

// One more than the documented AUTH_LIMIT (5/min) and API_LIMIT (60/min) per CLAUDE.md's security section.
private const val AUTH_LIMIT_PER_MINUTE = 5
private const val API_LIMIT_PER_MINUTE = 60

/**
 * Checks the two documented rate limits CLAUDE.md commits to (OWASP API4:2023, Unrestricted
 * Resource Consumption): `AUTH_LIMIT` 5/min per source IP and `API_LIMIT` 60/min per authenticated
 * user. Both are exercised end to end over HTTP against the real limiter, not by reading
 * `RateLimitConfig.kt` and restating its numbers back at it.
 */
internal suspend fun rateLimitProbes(
    api: ProbeApi,
    accounts: ProbeAccountFactory,
): List<ProbeOutcome> =
    listOf(
        probe(
            ProbeCategory.UNRESTRICTED_RESOURCE_CONSUMPTION,
            "the per-source-IP auth rate limit allows exactly its documented quota, then rejects",
            "$AUTH_LIMIT_PER_MINUTE logins from one address all succeed; the very next one from the same " +
                "address within the window -> 429",
        ) {
            val account = accounts.register("BUYER")
            val address = "198.51.100.${(1..254).random()}"
            val headers = mapOf("X-Forwarded-For" to forwardedFor(address))
            val statuses =
                (1..AUTH_LIMIT_PER_MINUTE + 1).map {
                    api
                        .postJson(
                            "/api/v1/auth/login",
                            json =
                                JsonObject(
                                    mapOf(
                                        "email" to JsonPrimitive(account.email),
                                        "password" to JsonPrimitive(account.password),
                                    ),
                                ).toString(),
                            headers = headers,
                        ).status
                }
            val allowed = statuses.take(AUTH_LIMIT_PER_MINUTE)
            val overflow = statuses.last()
            when {
                allowed.any { it != HTTP_OK } -> {
                    fail("a login within the documented quota was rejected: $statuses")
                }

                overflow != HTTP_TOO_MANY_REQUESTS -> {
                    fail(
                        "the login past the documented quota was not rate-limited: $statuses",
                    )
                }

                else -> {
                    pass()
                }
            }
        },
        probe(
            ProbeCategory.UNRESTRICTED_RESOURCE_CONSUMPTION,
            "the per-authenticated-user API rate limit allows exactly its documented quota, then rejects",
            "$API_LIMIT_PER_MINUTE telemetry requests from one vendor all pass the rate limiter (whatever their " +
                "own business-logic outcome); the next one within the window -> 429",
        ) {
            val vendor = accounts.register("VENDOR")
            val statuses =
                coroutineScope {
                    (1..API_LIMIT_PER_MINUTE + 1)
                        .map {
                            async {
                                api
                                    .postJson(
                                        "/api/v1/vehicle-tracking/telemetry",
                                        minimalTelemetryBody(),
                                        vendor.accessToken,
                                    ).status
                            }
                        }.awaitAll()
                }
            val rateLimited = statuses.count { it == HTTP_TOO_MANY_REQUESTS }
            if (rateLimited >= 1) pass() else fail("none of ${statuses.size} requests were rate-limited: $statuses")
        },
    )

private fun minimalTelemetryBody(): String =
    JsonObject(
        mapOf(
            "points" to
                JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "latitude" to JsonPrimitive(0.0),
                                "longitude" to JsonPrimitive(0.0),
                                "recordedAt" to JsonPrimitive(Instant.now().toString()),
                            ),
                        ),
                    ),
                ),
        ),
    ).toString()
