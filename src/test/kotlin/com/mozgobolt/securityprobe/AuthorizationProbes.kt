/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.securityprobe

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404

private data class VendorFixture(
    val account: ProbeAccount,
    val companyId: Int,
    val vehicleId: Int,
)

/**
 * Checks that a role-gated endpoint rejects the wrong role (OWASP API5:2023, Broken Function Level
 * Authorization) and that a resource scoped to one tenant is invisible/unusable to another (API1,
 * Broken Object Level Authorization). Every expectation is what the OpenAPI doc and the handlers'
 * own documented error vocabulary (`VehicleError.NOT_A_MEMBER`, `SavedLocationError.NOT_FOUND`, ...)
 * already commit to, checked by calling the real endpoint — not derived from reading what a
 * particular code path currently happens to return.
 */
internal suspend fun authorizationProbes(
    api: ProbeApi,
    accounts: ProbeAccountFactory,
): List<ProbeOutcome> {
    val vendorA = accounts.vendorFixture(api, "Fleet Alpha")
    val vendorB = accounts.vendorFixture(api, "Fleet Bravo")
    val buyerA = accounts.register("BUYER")
    val buyerB = accounts.register("BUYER")
    val buyerASavedLocationId = api.createSavedLocation(buyerA.accessToken)
    val buyerAInstallationId = "probe-device-${buyerA.userId}"
    api.postJson(
        "/api/v1/device-installations",
        json =
            JsonObject(
                mapOf(
                    "installationId" to JsonPrimitive(buyerAInstallationId),
                    "platform" to JsonPrimitive("ANDROID"),
                ),
            ).toString(),
        accessToken = buyerA.accessToken,
    )

    return listOf(
        functionLevelProbe(
            api,
            "a BUYER token cannot submit vendor telemetry",
            "/api/v1/vehicle-tracking/telemetry",
            buyerA,
            body = validTelemetryBody(),
        ),
        functionLevelProbe(
            api,
            "a VENDOR token cannot open the buyer live map",
            "/api/v1/vehicle-tracking/live?lat=0&lon=0",
            vendorA.account,
            get = true,
        ),
        functionLevelProbe(
            api,
            "a BUYER token cannot create a company (vendor-only)",
            "/api/v1/companies",
            buyerA,
            body =
                JsonObject(
                    mapOf("name" to JsonPrimitive("Should Be Rejected")),
                ).toString(),
        ),
        probe(
            ProbeCategory.BROKEN_FUNCTION_LEVEL_AUTHORIZATION,
            "a VENDOR token cannot ping a vehicle (ping is buyer-only)",
            "POST /vehicles/{id}/ping with a VENDOR token -> 403",
        ) {
            val response =
                api.postJson(
                    "/api/v1/vehicles/${vendorA.vehicleId}/ping",
                    json =
                        JsonObject(
                            mapOf("latitude" to JsonPrimitive(0.0), "longitude" to JsonPrimitive(0.0)),
                        ).toString(),
                    accessToken = vendorA.account.accessToken,
                )
            expectForbidden(response)
        },
        probe(
            ProbeCategory.BROKEN_FUNCTION_LEVEL_AUTHORIZATION,
            "a company member who is not its admin cannot read its invite code",
            "GET /companies/{id}/invite-code as a non-admin member -> 403 NOT_ADMIN",
        ) {
            val memberVendor = accounts.register("VENDOR")
            val code =
                api
                    .get(
                        "/api/v1/companies/${vendorA.companyId}/invite-code",
                        accessToken = vendorA.account.accessToken,
                    ).stringField("inviteCode")
            checkNotNull(code) { "could not read the admin's own invite code to join with" }
            val joined =
                api.postJson(
                    "/api/v1/companies/join",
                    JsonObject(mapOf("inviteCode" to JsonPrimitive(code))).toString(),
                    memberVendor.accessToken,
                )
            if (joined.status !=
                HttpStatusCode.OK.value
            ) {
                return@probe fail("the member fixture could not even join: HTTP ${joined.status} ${joined.body}")
            }

            val response =
                api.get(
                    "/api/v1/companies/${vendorA.companyId}/invite-code",
                    accessToken = memberVendor.accessToken,
                )
            if (response.status ==
                HTTP_FORBIDDEN
            ) {
                pass()
            } else {
                fail("expected 403, got HTTP ${response.status} ${response.body}")
            }
        },
        probe(
            ProbeCategory.BROKEN_OBJECT_LEVEL_AUTHORIZATION,
            "a vendor cannot patch another company's vehicle",
            "PATCH /companies/{companyId}/vehicles/{id} for vendor B's vehicle, using vendor A's token and " +
                "vendor B's companyId -> rejected (vendor A is not a member of that company)",
        ) {
            val response =
                api.patchJson(
                    "/api/v1/companies/${vendorB.companyId}/vehicles/${vendorB.vehicleId}",
                    json = JsonObject(mapOf("licensePlate" to JsonPrimitive("HACKED-1"))).toString(),
                    accessToken = vendorA.account.accessToken,
                )
            expectForbidden(response)
        },
        probe(
            ProbeCategory.BROKEN_OBJECT_LEVEL_AUTHORIZATION,
            "a vendor cannot archive another company's vehicle",
            "POST /companies/{companyId}/vehicles/{id}/archive for vendor B's vehicle, using vendor A's " +
                "token -> rejected",
        ) {
            val response =
                api.postJson(
                    "/api/v1/companies/${vendorB.companyId}/vehicles/${vendorA.vehicleId}/archive",
                    "{}",
                    vendorA.account.accessToken,
                )
            expectForbidden(response)
        },
        probe(
            ProbeCategory.BROKEN_OBJECT_LEVEL_AUTHORIZATION,
            "a vendor cannot list another company's members",
            "GET /companies/{id}/members for a company the caller never joined -> 403 NOT_A_MEMBER",
        ) {
            expectForbidden(
                api.get("/api/v1/companies/${vendorB.companyId}/members", accessToken = vendorA.account.accessToken),
            )
        },
        probe(
            ProbeCategory.BROKEN_OBJECT_LEVEL_AUTHORIZATION,
            "a buyer cannot delete another buyer's saved location",
            "DELETE /saved-locations/{id} for buyer A's saved location using buyer B's token -> 404 " +
                "(never a silent 204), and the location still belongs to buyer A afterward",
        ) {
            val deleteAsOther =
                api.delete(
                    "/api/v1/saved-locations/$buyerASavedLocationId",
                    accessToken = buyerB.accessToken,
                )
            if (deleteAsOther.status != HTTP_NOT_FOUND) {
                return@probe fail("expected 404, got HTTP ${deleteAsOther.status} ${deleteAsOther.body}")
            }
            val stillThere = api.get("/api/v1/saved-locations", accessToken = buyerA.accessToken).body
            if (buyerASavedLocationId.toString() in
                stillThere
            ) {
                pass()
            } else {
                fail("buyer A's saved location was gone after buyer B's delete attempt")
            }
        },
        probe(
            ProbeCategory.BROKEN_OBJECT_LEVEL_AUTHORIZATION,
            "a buyer cannot unregister another buyer's device installation",
            "DELETE /device-installations/{installationId} for buyer A's installation id using buyer B's token -> 404",
        ) {
            val response =
                api.delete(
                    "/api/v1/device-installations/$buyerAInstallationId",
                    accessToken = buyerB.accessToken,
                )
            if (response.status ==
                HTTP_NOT_FOUND
            ) {
                pass()
            } else {
                fail("expected 404, got HTTP ${response.status} ${response.body}")
            }
        },
    )
}

private suspend fun ProbeAccountFactory.vendorFixture(
    api: ProbeApi,
    companyName: String,
): VendorFixture {
    val account = register("VENDOR")
    val companyResponse =
        api.postJson(
            "/api/v1/companies",
            JsonObject(mapOf("name" to JsonPrimitive(companyName))).toString(),
            account.accessToken,
        )
    val companyId = companyResponse.field("id")?.toString()?.toIntOrNull()
    checkNotNull(companyId) {
        "could not create a probe fixture company: HTTP ${companyResponse.status} ${companyResponse.body}"
    }

    val vehicleResponse =
        api.postJson(
            "/api/v1/companies/$companyId/vehicles",
            JsonObject(
                mapOf(
                    "label" to JsonPrimitive("Probe Vehicle"),
                    "licensePlate" to JsonPrimitive("PR-${account.userId}"),
                ),
            ).toString(),
            account.accessToken,
        )
    val vehicleId = vehicleResponse.field("id")?.toString()?.toIntOrNull()
    checkNotNull(vehicleId) {
        "could not create a probe fixture vehicle: HTTP ${vehicleResponse.status} ${vehicleResponse.body}"
    }

    return VendorFixture(account, companyId, vehicleId)
}

private suspend fun ProbeApi.createSavedLocation(accessToken: String): Int {
    val body =
        JsonObject(
            mapOf(
                "label" to JsonPrimitive("Home"),
                "latitude" to JsonPrimitive(0.0),
                "longitude" to JsonPrimitive(0.0),
                "radiusKm" to JsonPrimitive(1.0),
            ),
        )
    val response = postJson("/api/v1/saved-locations", body.toString(), accessToken)
    return checkNotNull(response.field("id")?.toString()?.toIntOrNull()) {
        "could not create a probe fixture saved location: HTTP ${response.status} ${response.body}"
    }
}

private suspend fun functionLevelProbe(
    api: ProbeApi,
    name: String,
    path: String,
    account: ProbeAccount,
    get: Boolean = false,
    body: String = "{}",
): ProbeOutcome =
    probe(ProbeCategory.BROKEN_FUNCTION_LEVEL_AUTHORIZATION, name, "$path with the wrong role's token -> 403") {
        val response =
            if (get) {
                api.get(
                    path,
                    accessToken = account.accessToken,
                )
            } else {
                api.postJson(path, body, account.accessToken)
            }
        expectForbidden(response)
    }

private fun validTelemetryBody(): String =
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

private fun expectForbidden(response: ProbeResponse): ProbeCheckResult =
    if (response.status ==
        HTTP_FORBIDDEN
    ) {
        pass()
    } else {
        fail("expected 403, got HTTP ${response.status} ${response.body}")
    }
