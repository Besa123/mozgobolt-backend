# ShelfLife

A Kotlin backend built on [Ktor](https://ktor.io), following feature-based Clean Architecture. See
[ARCHITECTURE.md](ARCHITECTURE.md) for how the code is organized and why, [CONTRIBUTING.md](CONTRIBUTING.md)
for local setup and the checks a PR needs to pass, and [docs/adr/](docs/adr/) for the reasoning behind specific
decisions (refresh-token rotation, results-over-exceptions, etc.). For the pantry-tracking product domain itself,
see [docs/domain/pantry.md](docs/domain/pantry.md).

## Stack

- **Kotlin** 2.3.21 / **JVM** 21, **Ktor** 3.5.2 (Netty engine)
- **PostgreSQL** via **Exposed** (ORM) + **HikariCP** (connection pooling) + **Flyway** (migrations, run automatically
  on startup)
- **JWT** auth (access + refresh tokens, refresh-token family rotation with theft detection — see
  [ADR 0004](docs/adr/0004-refresh-token-family-rotation.md))
- Structured JSON logging (Logback + Logstash encoder), request-correlated via call IDs
- **Resilience4j** retry + circuit-breaker around external API calls (email today — see
  [ADR 0005](docs/adr/0005-resilience4j-for-external-calls.md))
- **detekt** + **ktlint** (zero-tolerance, gated in CI) + **Jacoco** coverage reporting

## Features

| Area          | Description                                                                                                        |
|---------------|--------------------------------------------------------------------------------------------------------------------|
| Auth          | Register, login, refresh (rotating), logout, logout-all, email verification/resend, password reset                 |
| Pantry        | Products, storage locations, quantity units, pantry entries — see [docs/domain/pantry.md](docs/domain/pantry.md)   |
| Rate limiting | Global 150 req/min baseline + tiered named limits (`AUTH_LIMIT`, `API_LIMIT`, `UPLOAD_LIMIT`)                      |
| Security      | CORS, CSP/HSTS/security headers, password peppering (password4j), account lockout policy                           |
| Reliability   | Idempotency keys, request timeouts, body-size limits, graceful shutdown, retry + circuit-breaker on external calls |
| Observability | `/health` (DB connectivity) and `/ready` endpoints, structured logs, request correlation IDs                       |

## Getting started

**Prerequisites:** JDK 21, a reachable PostgreSQL instance.

```bash
cp .env.example .env      # fill in DB credentials, JWT secret, password pepper, etc.
./gradlew run              # starts the server; Flyway migrations run automatically
```

The server listens on `http://localhost:8080` (`PORT` env var to override); console output is structured JSON. See
`.env.example` for all configuration variables. Leaving `RESEND_API_KEY` blank falls back to a logging-only email
service for local dev.

## Common tasks

| Task                                     | Description                                                                    |
|------------------------------------------|--------------------------------------------------------------------------------|
| `./gradlew run`                          | Start the server                                                               |
| `./gradlew test`                         | Run the test suite (Docker-dependent Testcontainers tests skip without Docker) |
| `./gradlew jacocoTestReport`             | Generate a coverage report at `build/reports/jacoco/test/html/index.html`      |
| `./gradlew ktlintCheck` / `ktlintFormat` | Check / auto-fix formatting                                                    |
| `./gradlew detekt`                       | Static analysis                                                                |
| `./gradlew build`                        | Full build                                                                     |

CI (`.github/workflows/ci.yml`) runs ktlint, detekt, tests, and coverage reporting on pushes and PRs targeting
`master`, against a real Postgres service container.

## API

All business routes are versioned under `/api/v1`
(see [Request lifecycle in ARCHITECTURE.md](ARCHITECTURE.md#request-lifecycle)); breaking changes get an `/api/v2` added
alongside rather than replacing v1.

**Interactive documentation:** start the server and visit `http://localhost:8080/swagger-ui` (spec:
`src/main/resources/openapi/documentation.json`) — the source of truth for exact endpoints and payloads. See
[Features](#features) above for the areas covered.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full local-setup and pre-PR checklist.
