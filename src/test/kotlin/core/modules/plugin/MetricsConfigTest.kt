/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.modules.plugin

import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.installTestModules
import com.mozgobolt.core.modules.AppConfig
import com.mozgobolt.core.modules.metrics.PrometheusMetrics
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetricsConfigTest {
    @Test
    fun `enabled metrics expose request histograms and pool gauges without measuring scrape requests`() {
        val metrics = PrometheusMetrics(enabled = true)
        val dataSource = hikaricpDataSource()

        try {
            testApplication {
                configureTestEnvironment()
                application {
                    installTestModules(prometheusMetrics = metrics)
                    dependencies {
                        provide<AppConfig> { testConfig(metricsEnabled = true) }
                        provide<DataSource> { dataSource }
                    }
                    configureMetrics()
                    routing {
                        get("/metrics-probe/{vehicleId}") {
                            call.respondText("ok")
                        }
                    }
                }

                assertEquals(HttpStatusCode.OK, client.get("/metrics-probe/1").status)
                assertEquals(HttpStatusCode.OK, client.get("/metrics-probe/2").status)
                val response = client.get("/metrics")
                val body = response.bodyAsText()

                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().contains("version=0.0.4"))
                assertContains(body, "ktor_http_server_requests_seconds_count")
                assertContains(body, "route=\"/metrics-probe/{vehicleId}\"")
                assertContains(body, "hikaricp_connections_active")
                assertFalse(body.contains("route=\"/metrics-probe/1\""))
                assertFalse(body.contains("route=\"/metrics-probe/2\""))
                assertFalse(body.contains("route=\"/metrics\""))
            }
        } finally {
            metrics.close()
            dataSource.close()
        }
    }

    @Test
    fun `metrics endpoint is absent by default`() {
        val metrics = PrometheusMetrics(enabled = false)

        testApplication {
            configureTestEnvironment()
            application {
                installTestModules(prometheusMetrics = metrics)
                dependencies {
                    provide<AppConfig> { testConfig(metricsEnabled = false) }
                }
                configureMetrics()
            }

            assertEquals(HttpStatusCode.NotFound, client.get("/metrics").status)
        }
    }

    private fun hikaricpDataSource(): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:postgresql://127.0.0.1:1/metrics-test"
                username = "unused"
                password = "unused"
                minimumIdle = 0
                maximumPoolSize = 1
                initializationFailTimeout = -1
            },
        )

    private fun testConfig(metricsEnabled: Boolean) =
        AppConfig(
            database = AppConfig.Database(url = "unused", user = "unused", password = "unused"),
            jwt = AppConfig.Jwt(secret = "unused", issuer = "unused", audience = "unused"),
            security = AppConfig.Security(passwordPepper = "unused"),
            cors = AppConfig.Cors(),
            email = AppConfig.Email(),
            metrics = AppConfig.Metrics(enabled = metricsEnabled),
        )
}
