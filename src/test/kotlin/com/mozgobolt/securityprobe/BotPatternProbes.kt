/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.securityprobe

import com.mozgobolt.loadtest.forwardedFor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

private const val HTTP_OK = 200
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_NOT_FOUND = 404
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_UNPROCESSABLE_ENTITY = 422
private const val HTTP_CREATED = 201

// LockoutPolicy.MAX_ATTEMPTS_BEFORE_LOCK: the account is locked once this many failed
// attempts have been recorded against it.
private const val ATTEMPTS_BEFORE_LOCK = 5

/**
 * Checks the app's defenses against patterns a real automated client — a credential-stuffing or
 * password-spraying bot, a mass internet vulnerability scanner, a replay tool reusing a captured
 * request — would actually run, as distinct from a single one-shot probe of a documented contract.
 * Every expectation here still comes from reading the app's own stated design (`LockoutPolicy`'s
 * KDoc, the idempotency store's payload-mismatch handling) before any request is sent, never from
 * guessing what a bot "should" be blocked by.
 */
internal suspend fun botPatternProbes(
    api: ProbeApi,
    accounts: ProbeAccountFactory,
): List<ProbeOutcome> =
    listOf(
        credentialStuffingLockoutProbe(api, accounts),
        scannerPathSweepProbe(api),
        idempotencyKeyReplayWithTamperedPayloadProbe(api, accounts),
    )

// Simulates credential stuffing: repeated wrong passwords against one target account, each attempt
// from a distinct source address, since a botnet's whole point is that no single address ever
// approaches the per-IP AUTH_LIMIT — the real question is whether a per-account defense exists
// independent of source IP. LockoutPolicy's own KDoc states it does.
private suspend fun credentialStuffingLockoutProbe(
    api: ProbeApi,
    accounts: ProbeAccountFactory,
): ProbeOutcome =
    probe(
        ProbeCategory.UNRESTRICTED_RESOURCE_CONSUMPTION,
        "a credential-stuffing run against one account is stopped by account lockout, not just the per-IP limit",
        "$ATTEMPTS_BEFORE_LOCK wrong passwords from $ATTEMPTS_BEFORE_LOCK distinct source addresses -> " +
            "401 each time; a further attempt from yet another address, with the CORRECT password, is then " +
            "still rejected (429 ACCOUNT_LOCKED) -- the account-level lockout holds even once the bot finds " +
            "the right password and keeps rotating its source address",
    ) {
        val account = accounts.register("BUYER")
        val wrongAttempts =
            (1..ATTEMPTS_BEFORE_LOCK).map { attempt ->
                api
                    .postJson(
                        "/api/v1/auth/login",
                        json = credentialsBody(account.email, "Wrong-Guess-$attempt-${Random.nextInt(Int.MAX_VALUE)}"),
                        headers = stuffingHeaders(attempt),
                    ).status
            }
        if (wrongAttempts.any { it != HTTP_UNAUTHORIZED }) {
            return@probe fail("expected every wrong attempt to be 401, got $wrongAttempts")
        }

        val correctPasswordAfterLockout =
            api.postJson(
                "/api/v1/auth/login",
                json = credentialsBody(account.email, account.password),
                headers = stuffingHeaders(ATTEMPTS_BEFORE_LOCK + 1),
            )
        if (correctPasswordAfterLockout.status == HTTP_TOO_MANY_REQUESTS) {
            pass()
        } else {
            fail(
                "the correct password was accepted (or failed some other way) immediately after lockout should " +
                    "have engaged: HTTP ${correctPasswordAfterLockout.status} ${correctPasswordAfterLockout.body}",
            )
        }
    }

private fun stuffingHeaders(index: Int): Map<String, String> =
    mapOf("X-Forwarded-For" to forwardedFor("203.0.113.${index + 10}"))

private fun credentialsBody(
    email: String,
    password: String,
): String = JsonObject(mapOf("email" to JsonPrimitive(email), "password" to JsonPrimitive(password))).toString()

// A representative sample of what mass internet scanners and bot crawlers probe on literally every
// host they can reach within minutes of it going live, looking for an exposed secret, a default
// admin panel, or a framework left in a debug/dev configuration.
private val SCANNER_TARGET_PATHS =
    listOf(
        "/.env",
        "/.git/config",
        "/.aws/credentials",
        "/wp-login.php",
        "/wp-admin/",
        "/phpmyadmin/",
        "/.well-known/security.txt",
        "/actuator/health",
        "/actuator/env",
        "/server-status",
        "/admin",
        "/debug",
        "/console",
        "/.DS_Store",
        "/config.php",
        "/swagger-ui.html",
    )

// Every one of SCANNER_TARGET_PATHS is a path this app never intentionally serves, so the only
// acceptable response is "nothing here" -- never a 200 with file contents or a framework default
// page revealing a stack/version.
private suspend fun scannerPathSweepProbe(api: ProbeApi): ProbeOutcome =
    probe(
        ProbeCategory.IMPROPER_INVENTORY_MANAGEMENT,
        "a sweep of common scanner/bot target paths finds nothing exposed",
        "${SCANNER_TARGET_PATHS.size} well-known scanner paths (.env, .git/config, wp-admin, actuator/env, " +
            "...) -> every one is 404, never 200 or a body containing file/credential-shaped content",
    ) {
        val exposed =
            SCANNER_TARGET_PATHS.mapNotNull { path ->
                val response = api.get(path)
                when {
                    response.status == HTTP_OK -> "$path responded 200: ${response.body.take(100)}"

                    response.status !in
                        setOf(
                            HTTP_NOT_FOUND,
                            HTTP_UNAUTHORIZED,
                        )
                    -> "$path responded HTTP ${response.status}"

                    else -> null
                }
            }
        if (exposed.isEmpty()) pass() else fail(exposed.joinToString("; "))
    }

// Simulates a replay/tampering tool: capture a legitimate request's Idempotency-Key and resend it
// with a different body, hoping either the new body is silently executed under the old
// deduplication record, or the old cached response is served for data never actually submitted.
private suspend fun idempotencyKeyReplayWithTamperedPayloadProbe(
    api: ProbeApi,
    accounts: ProbeAccountFactory,
): ProbeOutcome =
    probe(
        ProbeCategory.SECURITY_MISCONFIGURATION,
        "reusing a captured Idempotency-Key with a different request body is rejected, not silently applied",
        "POST /saved-locations twice with the same Idempotency-Key but a different label the second time -> " +
            "422 IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD, and only the first location is ever created",
    ) {
        val buyer = accounts.register("BUYER")
        val key = "probe-replay-${Random.nextInt(Int.MAX_VALUE)}"
        val headers = mapOf("Idempotency-Key" to key)

        val first =
            api.postJson(
                "/api/v1/saved-locations",
                savedLocationBody("Original Label"),
                buyer.accessToken,
                headers,
            )
        if (first.status != HTTP_CREATED) {
            return@probe fail("the first request itself was not accepted: HTTP ${first.status} ${first.body}")
        }

        val replay =
            api.postJson(
                "/api/v1/saved-locations",
                savedLocationBody("Tampered Label"),
                buyer.accessToken,
                headers,
            )
        if (replay.status != HTTP_UNPROCESSABLE_ENTITY) {
            return@probe fail("expected 422, got HTTP ${replay.status} ${replay.body}")
        }

        val saved = api.get("/api/v1/saved-locations", buyer.accessToken).body
        if ("Tampered Label" in saved) fail("the tampered replay's label was applied anyway") else pass()
    }

private fun savedLocationBody(label: String): String =
    JsonObject(
        mapOf(
            "label" to JsonPrimitive(label),
            "latitude" to JsonPrimitive(0.0),
            "longitude" to JsonPrimitive(0.0),
            "radiusKm" to JsonPrimitive(1.0),
        ),
    ).toString()
