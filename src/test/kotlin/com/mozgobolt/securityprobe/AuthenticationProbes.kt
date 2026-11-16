/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.securityprobe

import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.seconds

private const val HTTP_UNAUTHORIZED = 401
private val base64Url = Base64.getUrlEncoder().withoutPadding()

// The SSE route re-checks session liveness every 30s (AccessTokenSessionStreamGuard); allow one
// extra cycle of slack before concluding a still-open stream never noticed the revocation.
private val SSE_REVOCATION_WAIT = 40.seconds

/**
 * Checks that only a correctly signed, well-formed, unexpired access token for an active session
 * is ever honored — OWASP API2:2023 (Broken Authentication). Every expectation here is the stated
 * contract (JWT verification pinned to one algorithm, refresh-token-reuse revokes the whole
 * family per CLAUDE.md) checked by sending the tampered artifact over the wire, not by reading how
 * the handler is implemented.
 */
internal suspend fun authenticationProbes(
    api: ProbeApi,
    sseHttp: HttpClient,
    baseUrl: String,
    accounts: ProbeAccountFactory,
): List<ProbeOutcome> =
    listOf(
        probe(
            ProbeCategory.BROKEN_AUTHENTICATION,
            "a protected endpoint rejects a request with no Authorization header",
            "no Authorization header -> 401",
        ) {
            expectUnauthorized(api.get("/api/v1/auth/me"))
        },
        probe(
            ProbeCategory.BROKEN_AUTHENTICATION,
            "a protected endpoint rejects a malformed Authorization header",
            "Authorization: not-a-bearer-token -> 401",
        ) {
            expectUnauthorized(api.get("/api/v1/auth/me", headers = mapOf("Authorization" to "not-a-bearer-token")))
        },
        probe(
            ProbeCategory.BROKEN_AUTHENTICATION,
            "a token with a tampered signature is rejected",
            "flipping the last character of a valid token's signature -> 401",
        ) {
            val account = accounts.register("BUYER")
            expectUnauthorized(api.get("/api/v1/auth/me", accessToken = account.accessToken.withTamperedSignature()))
        },
        probe(
            ProbeCategory.BROKEN_AUTHENTICATION,
            "an 'alg: none' forged token is rejected",
            "a token whose header claims alg=none and carries no signature -> 401 (classic JWT alg-confusion forgery)",
        ) {
            val account = accounts.register("BUYER")
            val forged = account.accessToken.rebuiltAsUnsignedAlgNoneForgery()
            expectUnauthorized(api.get("/api/v1/auth/me", accessToken = forged))
        },
        probe(
            ProbeCategory.BROKEN_AUTHENTICATION,
            "a token re-signed with a commonly guessed secret is rejected",
            "re-signing a real token's claims with a short list of well-known default/placeholder HMAC secrets -> 401",
        ) {
            val account = accounts.register("BUYER")
            val stillRejected =
                COMMONLY_GUESSED_SECRETS.all { guess ->
                    api.get("/api/v1/auth/me", accessToken = account.accessToken.reSignedWith(guess)).status ==
                        HTTP_UNAUTHORIZED
                }
            if (stillRejected) pass() else fail("a token forged with a commonly guessed secret was accepted")
        },
        probe(
            ProbeCategory.BROKEN_AUTHENTICATION,
            "refresh-token reuse revokes the whole token family, not just the reused token",
            "reusing an already-rotated refresh token -> 401 TOKEN_REUSE_DETECTED, and the legitimately " +
                "rotated replacement issued just before the reuse is also invalidated (CLAUDE.md: " +
                "'Refresh-token reuse invalidates the whole family')",
        ) {
            val account = accounts.register("BUYER")
            val rotated = api.postJson("/api/v1/auth/refresh", refreshBody(account.refreshToken))
            if (rotated.status != HttpStatusCode.OK.value) {
                return@probe fail(
                    "the first, legitimate refresh was itself rejected: HTTP ${rotated.status} ${rotated.body}",
                )
            }
            val rotatedRefreshToken =
                rotated.stringField("refreshToken") ?: return@probe fail("refresh response had no refreshToken field")

            val reuseResponse = api.postJson("/api/v1/auth/refresh", refreshBody(account.refreshToken))
            if (reuseResponse.status != HTTP_UNAUTHORIZED) {
                return@probe fail(
                    "reusing the old refresh token was not rejected: " +
                        "HTTP ${reuseResponse.status} ${reuseResponse.body}",
                )
            }

            val replacementResponse = api.postJson("/api/v1/auth/refresh", refreshBody(rotatedRefreshToken))
            if (replacementResponse.status == HttpStatusCode.OK.value) {
                fail("the family's legitimately rotated replacement token still worked after a reuse was detected")
            } else {
                pass()
            }
        },
        probe(
            ProbeCategory.BROKEN_AUTHENTICATION,
            "a logged-out-everywhere session's live-map stream stops delivering within its recheck window",
            "GET /vehicle-tracking/live stays open across /auth/logout-all only until the stream's own " +
                "~30s session-liveness recheck notices the revoked family, then it closes",
        ) {
            val account = accounts.register("BUYER")
            val endState =
                watchLiveMap(
                    http = sseHttp,
                    baseUrl = baseUrl,
                    accessToken = account.accessToken,
                    latitude = 0.0,
                    longitude = 0.0,
                    radiusKm = 1.0,
                    watchFor = SSE_REVOCATION_WAIT,
                ) {
                    val logoutAll =
                        api.postJson(
                            "/api/v1/auth/logout-all",
                            json = "{}",
                            accessToken = account.accessToken,
                        )
                    check(logoutAll.status == HttpStatusCode.OK.value) {
                        "logout-all itself failed: HTTP ${logoutAll.status} ${logoutAll.body}"
                    }
                }
            if (endState == SseEndState.CLOSED) {
                pass()
            } else {
                fail("the live-map stream was still open ${SSE_REVOCATION_WAIT.inWholeSeconds}s after logout-all")
            }
        },
    )

private fun expectUnauthorized(response: ProbeResponse): ProbeCheckResult =
    if (response.status ==
        HTTP_UNAUTHORIZED
    ) {
        pass()
    } else {
        fail("expected 401, got HTTP ${response.status} ${response.body}")
    }

private fun refreshBody(refreshToken: String): String =
    JsonObject(mapOf("refreshToken" to JsonPrimitive(refreshToken))).toString()

private fun String.withTamperedSignature(): String {
    val parts = split('.')
    val signature = parts[2]
    val flipped = signature.dropLast(1) + if (signature.last() == 'A') 'B' else 'A'
    return "${parts[0]}.${parts[1]}.$flipped"
}

private fun String.rebuiltAsUnsignedAlgNoneForgery(): String {
    val payload = split('.')[1]
    val noneHeader = base64Url.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
    return "$noneHeader.$payload."
}

private fun String.reSignedWith(secret: String): String {
    val parts = split('.')
    val signingInput = "${parts[0]}.${parts[1]}"
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
    val signature = base64Url.encodeToString(mac.doFinal(signingInput.toByteArray()))
    return "$signingInput.$signature"
}

private val COMMONLY_GUESSED_SECRETS =
    listOf("secret", "changeme", "password", "jwt-secret", "your-256-bit-secret")
