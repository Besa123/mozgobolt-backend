/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.modules.plugin

import com.mozgobolt.core.modules.AppConfig
import com.mozgobolt.core.modules.metrics.PrometheusMetrics
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import javax.sql.DataSource

private const val PROMETHEUS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"
private val UNMEASURED_PATHS = setOf("/health", "/ready", "/metrics", "/openapi.json", "/swagger-ui")

fun Application.configureMetrics() {
    val appConfig: AppConfig by dependencies
    if (!appConfig.metrics.enabled) return

    val metrics: PrometheusMetrics by dependencies
    val registry =
        checkNotNull(metrics.registry) {
            "METRICS_ENABLED is true but the Prometheus registry was not initialized"
        }
    val dataSource: DataSource by dependencies
    val hikariDataSource =
        dataSource as? HikariDataSource
            ?: error("METRICS_ENABLED requires the application's HikariDataSource for pool metrics")

    install(MicrometerMetrics) {
        this.registry = registry
        distributionStatisticConfig =
            DistributionStatisticConfig
                .Builder()
                .percentilesHistogram(true)
                .build()
        meterBinders = meterBinders + HikariPoolMetrics(hikariDataSource)
        filter { call -> call.request.path() !in UNMEASURED_PATHS }
    }

    routing {
        get("/metrics") {
            call.respondText(
                text = registry.scrape(),
                contentType = ContentType.parse(PROMETHEUS_CONTENT_TYPE),
            )
        }
    }
}

private class HikariPoolMetrics(
    private val dataSource: HikariDataSource,
) : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        registerGauge(registry, "hikaricp.connections.active", "Connections currently borrowed") {
            it.hikariPoolMXBean?.run { activeConnections.toDouble() } ?: 0.0
        }
        registerGauge(registry, "hikaricp.connections.idle", "Idle connections in the pool") {
            it.hikariPoolMXBean?.run { idleConnections.toDouble() } ?: 0.0
        }
        registerGauge(registry, "hikaricp.connections.pending", "Threads waiting for a connection") {
            it.hikariPoolMXBean?.run { threadsAwaitingConnection.toDouble() } ?: 0.0
        }
        registerGauge(registry, "hikaricp.connections.total", "Total connections in the pool") {
            it.hikariPoolMXBean?.run { totalConnections.toDouble() } ?: 0.0
        }
        registerGauge(registry, "hikaricp.connections.max", "Maximum configured pool size") {
            it.maximumPoolSize.toDouble()
        }
        registerGauge(registry, "hikaricp.connections.min", "Minimum configured idle connections") {
            it.minimumIdle.toDouble()
        }
    }

    private fun registerGauge(
        registry: MeterRegistry,
        name: String,
        description: String,
        value: (HikariDataSource) -> Double,
    ) {
        Gauge
            .builder(name, dataSource) { source -> value(source) }
            .description(description)
            .tag("pool", dataSource.poolName)
            .register(registry)
    }
}
