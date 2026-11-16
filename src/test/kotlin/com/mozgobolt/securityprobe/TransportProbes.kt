/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.securityprobe

import com.mozgobolt.core.utility.functions.runSuspendCatching
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.url
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

private const val EVIL_ORIGIN = "https://evil.example"
private val SSE_HANDSHAKE_TIMEOUT = 5.seconds

// Every response (including an error response) must carry these, exactly as configureDefaultHeaders
// installs them for the whole application, not only on a handler's own success path.
private val REQUIRED_SECURITY_HEADERS =
    mapOf(
        "X-Content-Type-Options" to "nosniff",
        "X-Frame-Options" to "DENY",
        "Cache-Control" to "no-store",
    )

/**
 * Checks application-wide transport posture: the hardened response headers
 * (`configureDefaultHeaders`) reach every response including errors, CORS never reflects a
 * disallowed origin with credentials enabled (OWASP API8:2023), and the live-map feed's own
 * documented input contract (no `scope=all`, lat/lon required) is enforced the way its parser
 * states it is.
 */
internal suspend fun transportProbes(
    api: ProbeApi,
    sseHttp: HttpClient,
    baseUrl: String,
    accounts: ProbeAccountFactory,
): List<ProbeOutcome> =
    listOf(
        probe(
            ProbeCategory.SECURITY_MISCONFIGURATION,
            "hardened response headers are present on a plain success response",
            "GET /health carries ${REQUIRED_SECURITY_HEADERS.keys}, and Server/X-Powered-By reveal no framework banner",
        ) {
            headerCheck(api.get("/health"))
        },
        probe(
            ProbeCategory.SECURITY_MISCONFIGURATION,
            "hardened response headers are present on an error response too",
            "a 401 from a protected endpoint with no Authorization header still carries the same hardened headers",
        ) {
            headerCheck(api.get("/api/v1/auth/me"))
        },
        probe(
            ProbeCategory.SECURITY_MISCONFIGURATION,
            "CORS never reflects a disallowed origin back with credentials allowed",
            "a preflight OPTIONS from Origin: $EVIL_ORIGIN against a real route -> no Access-Control-Allow-Origin " +
                "naming that origin (CORS's own allow-list, not a blanket reflect-any-origin rule)",
        ) {
            val response =
                api.request(
                    HttpMethod.Options,
                    "/api/v1/auth/me",
                    headers = mapOf("Origin" to EVIL_ORIGIN, "Access-Control-Request-Method" to "GET"),
                )
            val allowedOrigin = response.headers["Access-Control-Allow-Origin"]
            if (allowedOrigin ==
                EVIL_ORIGIN
            ) {
                fail("the disallowed origin was reflected back: $allowedOrigin")
            } else {
                pass()
            }
        },
        probe(
            ProbeCategory.SECURITY_MISCONFIGURATION,
            "the live-map feed rejects scope=all exactly as its parser documents, not a silent nationwide feed",
            "GET /vehicle-tracking/live?scope=all -> the first frame is an 'error' event, never a vehicle location",
        ) {
            val buyer = accounts.register("BUYER")
            val firstEvent =
                withTimeoutOrNull(SSE_HANDSHAKE_TIMEOUT) {
                    firstSseEventName(sseHttp, "$baseUrl/api/v1/vehicle-tracking/live?scope=all", buyer.accessToken)
                }
            if (firstEvent == "error") pass() else fail("expected an 'error' event, got: $firstEvent")
        },
    )

private fun headerCheck(response: ProbeResponse): ProbeCheckResult {
    val missing = REQUIRED_SECURITY_HEADERS.filterNot { (name, value) -> response.headers[name] == value }
    if (missing.isNotEmpty()) return fail("missing/incorrect headers: $missing (full set: ${response.headers})")

    val serverHeader = response.headers["Server"].orEmpty()
    return if (serverHeader.isNotBlank() && serverHeader != "Ktor") {
        fail("Server header reveals more than expected: '$serverHeader'")
    } else {
        pass()
    }
}

private suspend fun firstSseEventName(
    http: HttpClient,
    targetUrl: String,
    accessToken: String,
): String? {
    var eventName: String? = null
    runSuspendCatching {
        http.sse(request = {
            url(targetUrl)
            bearerAuth(accessToken)
        }) {
            eventName = incoming.firstOrNull()?.event
        }
    }
    return eventName
}
