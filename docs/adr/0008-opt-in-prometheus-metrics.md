# ADR 0008: Opt-in Prometheus metrics

**Status:** Accepted

## Context

Operational baselines are needed for request latency, JVM and database-pool pressure, live-map activity, and the
telemetry history writer. The application does not currently have a dedicated private management listener, and the
metrics endpoint does not use application authentication.

## Decision

Use Ktor's Micrometer integration with a Prometheus registry. Keep `METRICS_ENABLED` false by default; when enabled,
serve `/metrics` and require deployments to restrict it to a trusted monitoring network or authenticated ingress.
Record request histograms using Ktor route templates, JVM defaults, Hikari pool gauges, and bounded operational metrics
for tracking. Provide an optional local Compose Prometheus service on the private Compose network, with its dashboard
published to loopback only.

## Consequences

The endpoint is explicit and can be scraped without adding a public API token mechanism. In the local Compose profile,
only loopback-published ports are exposed and Prometheus scrapes the app over the Compose network. Production operators
must apply deployment-owned network access control before enabling it. Histograms are aggregatable across instances;
raw request paths, user IDs, and vehicle IDs are not metric labels. This adds metrics, not distributed tracing or an
SLO.
