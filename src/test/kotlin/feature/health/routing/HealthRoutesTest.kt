/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.health.routing

import com.mozgobolt.core.data.messaging.NoOpMessageRelay
import com.mozgobolt.core.di.MESSAGE_RELAY_ATTRIBUTE
import com.mozgobolt.core.domain.messaging.MessageRelay
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HealthRoutesTest {
    @Test
    fun `health reports liveness without probing the database`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { infrastructureRoutes(fakeDataSource(connection = null)) }
            }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `ready reports ready when the connection is valid`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { infrastructureRoutes(fakeDataSource(fakeConnection(isValid = true))) }
            }

            val response = client.get("/ready")

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `ready reports unavailable when the connection cannot be established`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { infrastructureRoutes(fakeDataSource(connection = null)) }
            }

            val response = client.get("/ready")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }

    @Test
    fun `ready reports unavailable when the connection reports itself as invalid`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { infrastructureRoutes(fakeDataSource(fakeConnection(isValid = false))) }
            }

            val response = client.get("/ready")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }

    @Test
    fun `ready reports unavailable when the message relay is disconnected`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                attributes.put(
                    MESSAGE_RELAY_ATTRIBUTE,
                    object : MessageRelay by NoOpMessageRelay() {
                        override val isReady = false
                    },
                )
                routing { infrastructureRoutes(fakeDataSource(fakeConnection(isValid = true))) }
            }

            val response = client.get("/ready")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.bodyAsText().contains("MESSAGE_RELAY_UNAVAILABLE"))
        }

    @Test
    fun `ready returns unavailable when acquiring a database connection stalls`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { infrastructureRoutes(slowDataSource()) }
            }

            val response = withTimeout(5.seconds) { client.get("/ready") }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }

    private fun slowDataSource(): DataSource =
        Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, method, _ ->
            if (method.name == "getConnection") {
                Thread.sleep(10_000)
                fakeConnection(isValid = true)
            } else {
                throw UnsupportedOperationException("unexpected call to DataSource.${method.name}")
            }
        } as DataSource

    private fun fakeConnection(isValid: Boolean): Connection =
        Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            when (method.name) {
                "isValid" -> isValid
                "close" -> null
                else -> throw UnsupportedOperationException("unexpected call to Connection.${method.name}")
            }
        } as Connection

    private fun fakeDataSource(connection: Connection?): DataSource =
        Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, method, _ ->
            if (method.name == "getConnection") {
                connection ?: throw SQLException("could not connect")
            } else {
                throw UnsupportedOperationException("unexpected call to DataSource.${method.name}")
            }
        } as DataSource
}
