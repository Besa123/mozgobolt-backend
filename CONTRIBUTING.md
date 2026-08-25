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

## Code conventions

Full detail lives in [CLAUDE.md](CLAUDE.md) (the canonical source of truth for AI-assisted and human contributions
alike) and [ARCHITECTURE.md](ARCHITECTURE.md). The short version:

- **Feature-based layout.** New business capability → `feature/<name>/{domain,data,routing,di}`. Shared infrastructure
  goes in `core/`.
- **Results over exceptions.** Business logic returns `AppResult<T, E>`; callers `.fold(onSuccess, onError)`. Don't
  throw for expected failure paths.
- **Interface-first.** Domain defines the interface; `data/`/`service/` implement it. Feature implementations use the
  `*I` suffix (e.g. `UserRepositoryI`).
- **Request DTOs implement `ValidatedRequest`** and provide `validate(): List<String>`. Never validate manually inside a
  handler — the `RequestValidation` plugin runs it for you.
- **Use the route helpers**, never inline body-limit/timeout/auth/rate-limit concerns:
  `validatedPost`/`validatedPut`/`validatedPatch`/`limitedPost` for body handling, `protectedApi { }` /
  `publicRateLimitedApi { }` for auth + rate limiting.
- **No wildcard imports.**
- **`runSuspendCatching`, not `runCatching`** — the latter swallows `CancellationException` and breaks coroutine
  cancellation.
- **External API calls use Resilience4j.** Wrap third-party calls with a `withXResilience { }` helper (e.g.
  `withEmailResilience { }`) — see `core/modules/plugin/ResilienceConfig.kt`.

## Security ground rules

Non-negotiable on every change — see the Security section of [CLAUDE.md](CLAUDE.md) for the full list. In short:
never store tokens in plain text, never log secrets/raw tokens/passwords, never leak internals in an error response,
secrets come from `AppConfig`/env only, and every new endpoint needs a deliberate rate-limit choice (`AUTH_LIMIT`,
`API_LIMIT`, or `UPLOAD_LIMIT` via `protectedApi`/`publicRateLimitedApi`).

## Commit & PR style

- Keep commits scoped to one logical change; write the message in the imperative ("Add X", not "Added X").
- Significant, hard-to-reverse choices get an ADR in the same PR — see [docs/adr/README.md](docs/adr/README.md) for when
  and how.
- **API changes must update the OpenAPI spec** (`src/main/resources/openapi/documentation.json`) — it's the contract
  Swagger UI serves.
