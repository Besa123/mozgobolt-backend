/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.securityprobe

import com.mozgobolt.loadtest.forwardedFor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import kotlin.random.Random

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_PAYLOAD_TOO_LARGE = 413
private const val MAX_TELEMETRY_BATCH_SIZE = 100
private const val DISPLAY_NAME_MAX_LENGTH = 100
private const val TINY_BODY_LIMIT_BYTES = 1024

// Classic SQL/script metacharacters this app's own display-name validator already blocks
// (`validateDisplayName`'s INVALID_CHARACTERS regex). Submitting them is the regression guard that
// matters regardless of where a field's validation happens to live: the request must come back as
// a clean 400 ErrorResponse, never a 500, and never with a database error or stack trace in the body.
private val INJECTION_LOOKING_STRINGS =
    listOf(
        "' OR '1'='1",
        "Robert'); DROP TABLE users;--",
        "<script>alert(1)</script>",
        "\u0000\u0000null-bytes\u0000",
    )

private val LEAK_MARKERS =
    listOf("Exception", "at com.mozgobolt", "org.jetbrains.exposed", "org.postgresql", "StackTrace")

/**
 * Checks that every input the server accepts over HTTP is a clean validation pass/fail and never a
 * crash or an internal leak (OWASP API8:2023, Security Misconfiguration, plus the classic injection
 * and resource-consumption categories it folds in). CLAUDE.md already commits to "Errors: always
 * ErrorResponse, never leak internals" and to documented batch/length limits; these probes check
 * that commitment against the real wire response, not against the implementation that happens to
 * provide it.
 */
internal suspend fun inputValidationProbes(
    api: ProbeApi,
    accounts: ProbeAccountFactory,
): List<ProbeOutcome> {
    val buyer = accounts.register("BUYER")

    return listOf(
        injectionMetacharacterProbe(api, buyer),
        probe(
            ProbeCategory.UNRESTRICTED_RESOURCE_CONSUMPTION,
            "a field over its documented maximum length is rejected, not silently truncated and accepted",
            "a ${DISPLAY_NAME_MAX_LENGTH + 1}-character saved-location label (still well under the " +
                "request's own body-size limit) -> 400, never 200/201",
        ) {
            val oversizedLabel = "x".repeat(DISPLAY_NAME_MAX_LENGTH + 1)
            val response =
                api.postJson(
                    "/api/v1/saved-locations",
                    savedLocationBody(label = oversizedLabel),
                    buyer.accessToken,
                )
            expectStatus(response, HTTP_BAD_REQUEST)
        },
        probe(
            ProbeCategory.UNRESTRICTED_RESOURCE_CONSUMPTION,
            "a raw request body larger than the endpoint's declared body-size limit is rejected",
            "a registration body well over its 1KB BodyLimit.TINY -> 413, not 400/500/a hang",
        ) {
            val oversizedField = "x".repeat(TINY_BODY_LIMIT_BYTES * 4)
            val response =
                api.postJson(
                    "/api/v1/auth/register",
                    json = registrationBodyWithOversizedField(oversizedField),
                    headers = freshOneOffAuthHeaders(),
                )
            expectStatus(response, HTTP_PAYLOAD_TOO_LARGE)
        },
        probe(
            ProbeCategory.UNRESTRICTED_RESOURCE_CONSUMPTION,
            "a telemetry batch over its documented maximum point count is rejected",
            "a batch of ${MAX_TELEMETRY_BATCH_SIZE + 1} points (one more than " +
                "TelemetryBatchRequestDto's MAX_BATCH_SIZE) -> 400",
        ) {
            val vendor = accounts.register("VENDOR")
            val points = telemetryBodyWithPoints(MAX_TELEMETRY_BATCH_SIZE + 1)
            val response = api.postJson("/api/v1/vehicle-tracking/telemetry", points, vendor.accessToken)
            expectStatus(response, HTTP_BAD_REQUEST)
        },
        probe(
            ProbeCategory.SECURITY_MISCONFIGURATION,
            "a malformed (non-JSON) body with a JSON content type is a clean 400, never a 500",
            "garbage bytes posted as the login body -> 400, with no leaked internal detail",
        ) {
            val response =
                api.postJson(
                    "/api/v1/auth/login",
                    json = "{not even close to json",
                    headers = freshOneOffAuthHeaders(),
                )
            when {
                response.status != HTTP_BAD_REQUEST -> {
                    fail(
                        "expected 400, got HTTP ${response.status} ${response.body.take(200)}",
                    )
                }

                response.leaksAnInternalDetail() -> {
                    fail(
                        "response leaked an internal detail: ${response.body.take(200)}",
                    )
                }

                else -> {
                    pass()
                }
            }
        },
        adminRoleRegistrationProbe(api),
    )
}

private suspend fun injectionMetacharacterProbe(
    api: ProbeApi,
    buyer: ProbeAccount,
): ProbeOutcome =
    probe(
        ProbeCategory.INJECTION,
        "injection-metacharacter strings in a free-text field are cleanly rejected, never a 500",
        "each of ${INJECTION_LOOKING_STRINGS.size} SQL/script-metacharacter-bearing labels sent to " +
            "POST /saved-locations -> 400 ErrorResponse every time, never a leaked internal detail",
    ) {
        val failures =
            INJECTION_LOOKING_STRINGS.mapNotNull { payload ->
                val response =
                    api.postJson(
                        "/api/v1/saved-locations",
                        savedLocationBody(label = payload),
                        buyer.accessToken,
                    )
                payload.describeFailureOrNull(response)
            }
        if (failures.isEmpty()) pass() else fail(failures.joinToString("; "))
    }

private fun String.describeFailureOrNull(response: ProbeResponse): String? =
    when {
        response.leaksAnInternalDetail() -> "payload ${take(
            20,
        )}... leaked an internal detail: ${response.body.take(200)}"

        response.status != HTTP_BAD_REQUEST -> "payload ${take(20)}... got HTTP ${response.status} instead of 400"

        else -> null
    }

private suspend fun adminRoleRegistrationProbe(api: ProbeApi): ProbeOutcome =
    probe(
        ProbeCategory.BROKEN_OBJECT_PROPERTY_LEVEL_AUTHORIZATION,
        "registering with a role value outside the two real roles never creates a privileged account",
        "POST /auth/register with role=\"ADMIN\" (not a UserRole) -> 400, and no account is created for that email",
    ) {
        val email = "security-probe-admin-role-${Random.nextInt(Int.MAX_VALUE)}@loadtest.invalid"
        val registerResponse =
            api.postJson(
                "/api/v1/auth/register",
                json = registrationBodyWithRole(email, role = "ADMIN"),
                headers = freshOneOffAuthHeaders(),
            )
        if (registerResponse.status != HTTP_BAD_REQUEST) {
            return@probe fail("expected 400, got HTTP ${registerResponse.status} ${registerResponse.body.take(200)}")
        }

        val loginResponse =
            api.postJson(
                "/api/v1/auth/login",
                json = credentialsBody(email, "whatever"),
                headers = freshOneOffAuthHeaders(),
            )
        if (loginResponse.status ==
            HTTP_UNAUTHORIZED
        ) {
            pass()
        } else {
            fail("an account for role=ADMIN appears to exist: HTTP ${loginResponse.status}")
        }
    }

private fun expectStatus(
    response: ProbeResponse,
    expected: Int,
): ProbeCheckResult =
    if (response.status ==
        expected
    ) {
        pass()
    } else {
        fail("expected $expected, got HTTP ${response.status} ${response.body.take(200)}")
    }

private fun ProbeResponse.leaksAnInternalDetail(): Boolean = LEAK_MARKERS.any { body.contains(it) }

private fun freshOneOffAuthHeaders(): Map<String, String> =
    mapOf("X-Forwarded-For" to forwardedFor(nextOneOffAuthClientAddress()))

private fun savedLocationBody(label: String): String =
    JsonObject(
        mapOf(
            "label" to JsonPrimitive(label),
            "latitude" to JsonPrimitive(0.0),
            "longitude" to JsonPrimitive(0.0),
            "radiusKm" to JsonPrimitive(1.0),
        ),
    ).toString()

private fun registrationBodyWithOversizedField(oversizedName: String): String =
    JsonObject(
        mapOf(
            "email" to JsonPrimitive("security-probe-oversized-${Random.nextInt(Int.MAX_VALUE)}@loadtest.invalid"),
            "password" to JsonPrimitive("Security-Probe-Only-Oversized-Field-2026"),
            "name" to JsonPrimitive(oversizedName),
            "role" to JsonPrimitive("BUYER"),
        ),
    ).toString()

private fun registrationBodyWithRole(
    email: String,
    role: String,
): String =
    JsonObject(
        mapOf(
            "email" to JsonPrimitive(email),
            "password" to JsonPrimitive("Security-Probe-Only-Admin-Role-Attempt-2026"),
            "name" to JsonPrimitive("Security Probe"),
            "role" to JsonPrimitive(role),
        ),
    ).toString()

private fun credentialsBody(
    email: String,
    password: String,
): String = JsonObject(mapOf("email" to JsonPrimitive(email), "password" to JsonPrimitive(password))).toString()

private fun telemetryBodyWithPoints(pointCount: Int): String =
    JsonObject(
        mapOf(
            "points" to
                JsonArray(
                    List(pointCount) {
                        JsonObject(
                            mapOf(
                                "latitude" to JsonPrimitive(0.0),
                                "longitude" to JsonPrimitive(0.0),
                                "recordedAt" to JsonPrimitive(Instant.now().toString()),
                            ),
                        )
                    },
                ),
        ),
    ).toString()
