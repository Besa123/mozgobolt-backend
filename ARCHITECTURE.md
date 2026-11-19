# Architecture

Ktor backend, feature-based Clean Architecture: `core/` (cross-cutting infra only, no business logic) +
`feature/<name>/{domain,data,service,routing,di}` per capability. Contributor rules: [CLAUDE.md](CLAUDE.md). Decision
rationale: [docs/adr/](docs/adr/).

## Layout notes not obvious from the folders

- `feature/user/` is the reference implementation of the full pattern (now also the home of role-gating —
  `RoleGuard.kt`); `feature/health/` is intentionally `routing/`-only (no domain logic).
- Rationale for feature-based over layer-based: [ADR 0002](docs/adr/0002-feature-based-clean-architecture.md).

## Request lifecycle

1. Ktor plugins (`core/modules/plugin/`) install in the order in `Modules.kt` — Sentry and optional metrics, resources,
   headers, JWT auth, CORS, content negotiation, rate limiting, call ID, validation, then status pages. Don't reorder
   without checking why each one sits where it does.
2. Routing groups everything under `/api/v1` (`core/routing/ApiVersion.kt`); a breaking change gets `/api/v2`
   added alongside, never a replacement.
3. Auth, rate limiting, body-size limits, timeouts, and validation are enforced by the route-builder wrappers (see
   CLAUDE.md) before a handler runs — never composed manually per route.
4. Handlers call a domain service returning `AppResult<T, E>` (rationale:
   [ADR 0003](docs/adr/0003-results-over-exceptions-for-business-logic.md)); unexpected exceptions are caught by
   `StatusPages` into a generic `ErrorResponse`.
5. `core/data/idempotency/` wraps creation/mutation endpoints where a retry could otherwise duplicate state.

Request-body limits have two layers: an early `Content-Length` rejection and Ktor's streaming `RequestBodyLimit`
installed globally and at route builders, so chunked requests without a declared length are still bounded. File
uploads additionally count multipart/file bytes while reading (see
[ADR 0007](docs/adr/0007-vehicle-image-storage-and-sanitization.md)).

## Load-bearing decisions — don't simplify these away without reading the ADR

- Refresh-token family rotation: reuse invalidates the whole family, not just one token —
  [ADR 0004](docs/adr/0004-refresh-token-family-rotation.md).
- Resilience4j circuit-breaker wraps retry, not the reverse; retry fires only on transient email errors —
  [ADR 0005](docs/adr/0005-resilience4j-for-external-calls.md).
- Multi-device sync: pull (`GET /sync?since=`) is the only source of truth; SSE push is a liveness hint, never required
  for correctness — [ADR 0006](docs/adr/0006-multi-device-sync-outbox-and-cursor-pull.md).
- Image uploads are decoded, size-limited, and re-encoded from pixels; production also requires ClamAV and fails
  closed if scanning is unavailable. These are complementary controls —
  [ADR 0007](docs/adr/0007-vehicle-image-storage-and-sanitization.md).

## Configuration

`AppConfig` (`core/modules/AppConfig.kt`) is typed, bound from `application.conf` (HOCON); secrets come only from
`${?ENV_VAR}` overrides. `.env` (dotenv-kotlin) is local-dev only — never overrides a real env var.

## Observability

Structured JSON logs (Logback/Logstash), request-correlated via `callId`. `/health` is a liveness check and does not
probe dependencies; `/ready` checks a real database connection on the blocking-DB dispatcher
(`feature/health/routing/HealthRoutes.kt`). Error tracking: every `logger.error` becomes a Sentry issue when
`SENTRY_DSN` is set (`core/modules/plugin/SentryConfig.kt`) — no-op, no code changes needed, when unset.

Prometheus metrics are opt-in with `METRICS_ENABLED=true`. `/metrics` reports Ktor request histograms with route
templates (not raw request paths), JVM defaults, Hikari pool gauges, live-map subscriber count, telemetry-history
buffer drops/retries, and writer flush age. The endpoint has no application authentication and must be restricted to a
trusted monitoring network or authenticated ingress ([ADR 0008](docs/adr/0008-opt-in-prometheus-metrics.md)).
Distributed tracing is not configured.

## Known gaps

No CD stage or distributed tracing. Metrics are opt-in and require a Prometheus-compatible scraper and a protected
network path. Kover enforces at least 92% total line coverage and 62% branch coverage in CI; Docker is required to
include the database, Valkey, and S3 integration tests in the measurement. See [ADR 0009](docs/adr/0009-containerized-runtime-and-local-compose.md)
for the container and local Compose boundary.
