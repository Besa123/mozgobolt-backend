package com.besa.shelflife.feature.health.routing

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthRoutesTest {
    @Test
    fun `health reports healthy when the connection is valid`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { infrastructureRoutes(fakeDataSource(fakeConnection(isValid = true))) }
            }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `health reports unhealthy when the connection cannot be established`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { infrastructureRoutes(fakeDataSource(connection = null)) }
            }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }

    @Test
    fun `health reports unhealthy when the connection reports itself as invalid`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { infrastructureRoutes(fakeDataSource(fakeConnection(isValid = false))) }
            }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }

    @Test
    fun `ready always reports ready regardless of database state`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { infrastructureRoutes(fakeDataSource(connection = null)) }
            }

            val response = client.get("/ready")

            assertEquals(HttpStatusCode.OK, response.status)
        }

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
            when (method.name) {
                "getConnection" -> connection ?: throw SQLException("could not connect")
                else -> throw UnsupportedOperationException("unexpected call to DataSource.${method.name}")
            }
        } as DataSource
}
