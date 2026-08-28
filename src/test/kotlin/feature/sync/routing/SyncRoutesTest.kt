package com.shelflife.feature.sync.routing

import com.shelflife.core.configureTestEnvironment
import com.shelflife.feature.sync.domain.model.SyncEntityType
import com.shelflife.feature.sync.domain.model.SyncOperation
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncRoutesTest {
    @Test
    fun `get sync without a since param returns everything from the beginning`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeSyncService()
            runBlocking { service.recordChange(1, SyncEntityType.PRODUCT, 5, SyncOperation.UPSERT) }
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
                service.recordChange(1, SyncEntityType.PRODUCT, 1, SyncOperation.UPSERT)
                service.recordChange(1, SyncEntityType.PRODUCT, 2, SyncOperation.UPSERT)
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
                service.recordChange(1, SyncEntityType.PRODUCT, 1, SyncOperation.UPSERT)
                service.recordChange(2, SyncEntityType.PRODUCT, 2, SyncOperation.UPSERT)
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
            runBlocking { service.recordChange(1, SyncEntityType.PRODUCT, 1, SyncOperation.UPSERT) }
            application { installSyncRoutesTestApp(service) }

            val response = getJson(SyncPaths.since(cursor = -1))

            assertEquals(HttpStatusCode.OK, response.status)
            val events = Json.parseToJsonElement(response.bodyAsText()).jsonObject["events"]!!.jsonArray
            assertEquals(1, events.size)
        }
}
