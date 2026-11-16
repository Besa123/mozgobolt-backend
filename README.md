# MozgoBolt

A Kotlin backend built on [Ktor](https://ktor.io), following feature-based Clean Architecture, powering real-time
GPS tracking for mobile vendors (buyers see where a vendor's vehicle currently is). See
[ARCHITECTURE.md](ARCHITECTURE.md) for how the code is organized and why, [CONTRIBUTING.md](CONTRIBUTING.md)
for local setup and the checks a PR needs to pass.

## Stack

- **Kotlin** 2.4.10 / **JVM** 21, **Ktor** 3.5.2 (Netty engine)
- **PostgreSQL** via **Exposed** (ORM) + **HikariCP** (connection pooling) + **Flyway** (migrations, run automatically
  on startup)
- **JWT** auth (access + refresh tokens, refresh-token family rotation with theft detection in
  `feature/user/service/UserServiceI.kt`)
- Structured JSON request logs (Logback + Logstash encoder) include request IDs and authenticated user IDs. Production
  writes to stdout and rolling files under `LOG_DIR` (default `./logs`); request logs omit IPs, user agents, device IDs,
  request bodies, emails, and tokens.
- **Resilience4j** retry + circuit-breaker around external API calls (see `core/resilience/`)
- **detekt** + **ktlint** (zero-tolerance, gated in CI) + **Kover** line and branch coverage reporting and gates

## Features

| Area          | Description                                                                                                        |
|---------------|--------------------------------------------------------------------------------------------------------------------|
| Auth          | Register (buyer/vendor role), login, current-account profile, refresh (rotating), logout, logout-all, email verification/resend, password reset |
| Vendors       | Companies (create/join/list memberships), vehicles, vehicle link/delink (`vehicle_assignments`)                   |
| Tracking      | Batched GPS telemetry ingestion, live SSE feed for buyers, in-memory fast path + durable slow-path history          |
| Rate limiting | Global 150 req/min per trusted source IP + tiered named limits (`AUTH_LIMIT`, `API_LIMIT`, `UPLOAD_LIMIT`)             |
| Security      | CORS, CSP/HSTS/security headers, password peppering (password4j), account lockout policy                           |
| Reliability   | Idempotency keys, request timeouts, body-size limits, graceful shutdown, retry + circuit-breaker on external calls |
| Observability | `/health` (DB connectivity) and `/ready` endpoints, structured logs, request correlation IDs                       |

Telemetry history uses a bounded in-memory buffer (up to 50,000 pending points). Failed batches retry in FIFO order
with per-point idempotency; when the buffer is full, only the history copy is dropped and live updates continue.
For multi-instance deployments, Redis relays each accepted telemetry batch as one best-effort Pub/Sub message.
Relay delivery is volatile and is not a durable history mechanism.

## Getting started

```bash
cp .env.example .env      # fill in DB credentials, JWT secret, password pepper, etc.
./gradlew run              # starts the server; Flyway migrations run automatically
```

The server listens on `http://localhost:8080` (`PORT` env var to override). Leaving `RESEND_API_KEY` blank falls back to
a logging-only email service for local dev.

## Live-location load testing

See [load-tests/README.md](load-tests/README.md) for the opt-in disposable local workload, a Kotlin coroutine/Flow runner
(`./gradlew runLiveLoadTest`): 100 moving vehicles, 500 buyers with skewed geography and map-session churn, proximity
alerts, and concurrent login bursts. It is intentionally restricted to loopback targets and reports measurements
without inventing latency SLOs.

## Security probe suite

See [security-probes/README.md](security-probes/README.md) for the opt-in disposable local probe suite
(`./gradlew runSecurityProbes`): black-box checks against the OWASP API Security Top 10 and CLAUDE.md's stated
invariants, run against the real app with disposable throwaway accounts. It found and led to fixes for two real bugs —
see that README's "What it found" section.

## Exposed migration preview

`V1__init_schema.sql` is generated from the Exposed table declarations registered in
`GenerateExposedMigrationPreview.kt`; `db/schema/v1_custom.sql` supplies PostgreSQL-only user-delete triggers for the
last-company-admin guard and vehicle-location cleanup that Exposed cannot generate. For future changes, set
`EXPOSED_MIGRATION_PREVIEW_JDBC_URL`, `EXPOSED_MIGRATION_PREVIEW_USER`, and `EXPOSED_MIGRATION_PREVIEW_PASSWORD` to a
local PostgreSQL database whose name ends in `_migration_preview`; its `public` schema must be empty. The helper
applies the committed Flyway history to that disposable database, then compares it with the Exposed table registry in
`GenerateExposedMigrationPreview.kt`.

```bash
./gradlew generateExposedMigrationPreview -PmigrationPreviewScriptName=V2__describe_change
```

Add any new table object to that registry, then give the task a new Flyway script name. Candidate SQL is written to
`build/exposed-migration-preview/`; the helper refuses to overwrite a preview and never applies it. Review the
generated SQL before promoting it to `src/main/resources/db/migration`, especially destructive statements, column
renames/type changes, data backfills, and partial-index predicate changes that Exposed cannot reliably detect.

To regenerate the greenfield V1, generate the full schema from an empty disposable database without first applying
Flyway, review `build/exposed-migration-preview/V1__init_schema.sql`, then explicitly promote that generated candidate:

```bash
./gradlew generateExposedInitialV1Candidate
# Review the generated SQL before replacing V1.
./gradlew promoteExposedInitialV1
```

The promotion task copies only that candidate over V1; it does not create a V2 migration. The initial-schema mode uses
the same complete table registry as the preview task and appends the checked-in custom trigger SQL.

## API

All business routes are versioned under `/api/v1`
(see [Request lifecycle in ARCHITECTURE.md](ARCHITECTURE.md#request-lifecycle)); breaking changes get an `/api/v2` added
alongside rather than replacing v1.

**Interactive documentation:** start the server and visit `http://localhost:8080/swagger-ui` (spec:
`src/main/resources/openapi/documentation.json`) — the source of truth for exact endpoints and payloads. See
[Features](#features) above for the areas covered.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full local-setup and pre-PR checklist.
