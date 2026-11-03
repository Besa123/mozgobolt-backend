/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.sync.routing

import com.mozgobolt.core.TEST_JWT_AUDIENCE
import com.mozgobolt.core.TEST_JWT_ISSUER
import com.mozgobolt.core.TEST_JWT_SECRET
import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.data.security.JwtTokenManager
import com.mozgobolt.core.utility.functions.DEVICE_ID_HEADER
import com.mozgobolt.feature.sync.domain.SyncEventHub
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncHint
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SyncRoutesTest {
    @Test
    fun `get sync without a since param returns everything from the beginning`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeSyncService()
            runBlocking { service.recordChange(1, SyncEntityType.VEHICLE, 5, SyncOperation.UPSERT) }
            application { installSyncRoutesTestApp(service) }

            val response = getJson(SyncPaths.since())

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1, body["events"]?.jsonArray?.size)
        }

    @Test
    fun `get sync without auth returns 401`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeSyncService()
            application { installSyncRoutesTestApp(service) }

            val response = getJson(SyncPaths.since(), userId = null)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `get sync with since only returns events past that cursor`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeSyncService()
            runBlocking {
                service.recordChange(1, SyncEntityType.VEHICLE, 1, SyncOperation.UPSERT)
                service.recordChange(1, SyncEntityType.VEHICLE, 2, SyncOperation.UPSERT)
            }
            application { installSyncRoutesTestApp(service) }

            val response = getJson(SyncPaths.since(cursor = 1))

            assertEquals(HttpStatusCode.OK, response.status)
            val events = Json.parseToJsonElement(response.bodyAsText()).jsonObject["events"]!!.jsonArray
            assertEquals(1, events.size)
            assertEquals(2, events[0].jsonObject["entityId"]?.jsonPrimitive?.int)
        }

    @Test
    fun `get sync never returns another user's events`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeSyncService()
            runBlocking {
                service.recordChange(1, SyncEntityType.VEHICLE, 1, SyncOperation.UPSERT)
                service.recordChange(2, SyncEntityType.VEHICLE, 2, SyncOperation.UPSERT)
            }
            application { installSyncRoutesTestApp(service) }

            val response = getJson(SyncPaths.since())

            assertEquals(HttpStatusCode.OK, response.status)
            val events = Json.parseToJsonElement(response.bodyAsText()).jsonObject["events"]!!.jsonArray
            assertEquals(1, events.size)
        }

    @Test
    fun `get sync with a negative cursor is harmless, treated the same as no cursor at all`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeSyncService()
            runBlocking { service.recordChange(1, SyncEntityType.VEHICLE, 1, SyncOperation.UPSERT) }
            application { installSyncRoutesTestApp(service) }

            val response = getJson(SyncPaths.since(cursor = -1))

            assertEquals(HttpStatusCode.OK, response.status)
            val events = Json.parseToJsonElement(response.bodyAsText()).jsonObject["events"]!!.jsonArray
            assertEquals(1, events.size)
        }

    @Test
    fun `get sync forwards a valid cursor and page limit and defaults malformed values`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeSyncService()
            application { installSyncRoutesTestApp(service) }

            val bounded =
                getJson(
                    SyncPaths.since(cursor = 23, limit = 5),
                    userId = 7,
                )
            val malformed =
                getJson(
                    "/api/v1/sync?since=not-a-number&limit=not-a-number",
                    userId = 8,
                )

            assertEquals(HttpStatusCode.OK, bounded.status)
            assertEquals(HttpStatusCode.OK, malformed.status)
            assertEquals(
                listOf(
                    FakeSyncService.ChangesSinceCall(userId = 7, cursor = 23, limit = 5),
                    FakeSyncService.ChangesSinceCall(userId = 8, cursor = 0, limit = null),
                ),
                service.changesSinceCalls,
            )
        }

    @Test
    fun `live sync streams events and a reconnect hint for a short-lived access token`() =
        testApplication {
            configureTestEnvironment()
            val eventHub =
                object : SyncEventHub {
                    override fun publish(
                        userId: Int,
                        eventId: Long,
                        originDeviceId: String?,
                    ) = Unit

                    override fun subscribe(userId: Int): Flow<SyncHint> =
                        flowOf(SyncHint(eventId = 57, originDeviceId = "another-device"))
                }
            application { installSyncRoutesTestApp(FakeSyncService(), eventHub) }
            val token =
                JwtTokenManager(
                    secret = TEST_JWT_SECRET,
                    audience = TEST_JWT_AUDIENCE,
                    issuer = TEST_JWT_ISSUER,
                    accessTokenExpiration = 10.seconds,
                ).generateAccessToken(userId = 7, role = "BUYER", familyId = "sync-test-family")

            val response =
                client.get("/api/v1/sync/live") {
                    bearerAuth(token)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("changed"))
            assertTrue(body.contains("57"))
            assertTrue(body.contains("reconnect"))
        }

    @Test
    fun `live sync suppresses own-device hints but forwards other and unknown origins`() =
        testApplication {
            configureTestEnvironment()
            val eventHub =
                object : SyncEventHub {
                    override fun publish(
                        userId: Int,
                        eventId: Long,
                        originDeviceId: String?,
                    ) = Unit

                    override fun subscribe(userId: Int): Flow<SyncHint> =
                        flowOf(
                            SyncHint(eventId = 56, originDeviceId = "device-A"),
                            SyncHint(eventId = 57, originDeviceId = "another-device"),
                            SyncHint(eventId = 58, originDeviceId = null),
                        )
                }
            application { installSyncRoutesTestApp(FakeSyncService(), eventHub) }
            val token =
                JwtTokenManager(
                    secret = TEST_JWT_SECRET,
                    audience = TEST_JWT_AUDIENCE,
                    issuer = TEST_JWT_ISSUER,
                    accessTokenExpiration = 10.seconds,
                ).generateAccessToken(userId = 7, role = "BUYER", familyId = "sync-test-family")

            val response =
                client.get("/api/v1/sync/live") {
                    bearerAuth(token)
                    header(DEVICE_ID_HEADER, " device-A ")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertFalse(body.contains("data: 56"))
            assertTrue(body.contains("data: 57"))
            assertTrue(body.contains("data: 58"))
        }
}
