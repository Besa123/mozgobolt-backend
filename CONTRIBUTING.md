# Contributing to ShelfLife

The rules the codebase enforces in CI, written down. For the "why" behind the structure, see
[ARCHITECTURE.md](ARCHITECTURE.md); for rationale behind specific decisions, see [docs/adr/](docs/adr/).

## Prerequisites

- JDK 21
- A local PostgreSQL instance (or point `DB_URL` at any reachable Postgres)
- `.env` file — copy `.env.example` to `.env` and fill in real values. Loaded automatically at startup via
  `dotenv-kotlin`, but only for variables not already set in the environment (CI/prod env vars always win).

## Local setup

```bash
cp .env.example .env    # fill in DB credentials, JWT secret, etc.
./gradlew test          # run the test suite (Testcontainers tests skip without Docker)
./gradlew run           # start the server
```

Flyway migrations run automatically on startup — no manual migration step needed.

## Before opening a PR

Run the same checks CI runs, in this order, and make sure all of them pass:

```bash
./gradlew ktlintCheck         # formatting — auto-fixable with ./gradlew ktlintFormat
./gradlew detekt              # static analysis, zero-tolerance (config/detekt.yml)
./gradlew test                # unit + integration tests
./gradlew jacocoTestReport    # coverage report at build/reports/jacoco/test/html/index.html
```

CI (`.github/workflows/ci.yml`) gates on all of the above except the coverage report (report-only, no hard threshold
yet). A PR that fails ktlint, detekt, or tests will not merge.

## Code conventions & security rules

[CLAUDE.md](CLAUDE.md) is the canonical rule set for both AI-assisted and human contributions — read it before writing
code. [ARCHITECTURE.md](ARCHITECTURE.md) explains the reasoning behind those rules.

## Commit & PR style

- Keep commits scoped to one logical change; write the message in the imperative ("Add X", not "Added X").
- Significant, hard-to-reverse choices get an ADR in the same PR — see [docs/adr/README.md](docs/adr/README.md) for when
  and how.
- **API changes must update the OpenAPI spec** (`src/main/resources/openapi/documentation.json`) — it's the contract
  Swagger UI serves.
