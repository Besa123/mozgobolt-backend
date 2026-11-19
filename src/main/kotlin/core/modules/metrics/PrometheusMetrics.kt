/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.modules.metrics

import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

/** Owns the optional Prometheus registry for one application instance. */
class PrometheusMetrics(
    enabled: Boolean,
) : AutoCloseable {
    /** The registry to scrape when metrics are enabled; `null` otherwise. */
    val registry: PrometheusMeterRegistry? =
        if (enabled) {
            PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        } else {
            null
        }

    override fun close() {
        registry?.close()
    }
}
