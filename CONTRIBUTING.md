# Contributing to ShelfLife

This is currently a single-maintainer project, but it's built to professional standards from day one — these are the
rules the codebase already enforces in CI, written down so anyone (including future-you)
can ramp up without archaeology.

For the "why" behind the structure, see [ARCHITECTURE.md](ARCHITECTURE.md). For rationale behind specific decisions,
see [docs/adr/](docs/adr/).

## Prerequisites

- JDK 21
- A local PostgreSQL instance (or point `DB_URL` at any reachable Postgres)
- `.env` file — copy `.env.example` to `.env` and fill in real values. Loaded automatically at startup via
  `dotenv-kotlin`, but only for variables not already set in the environment (CI/prod env vars always win).

## Local setup

```bash
cp .env.example .env      # fill in DB credentials, JWT secret, etc.
./gradlew test            # run the test suite (spins up Testcontainers Postgres)
./gradlew run              # start the server
```

Flyway migrations run automatically on startup — no manual migration step needed.

## Before opening a PR

Run the same checks CI runs, in this order, and make sure all of them pass:

```bash
./gradlew ktlintCheck   # formatting — auto-fixable with ./gradlew ktlintFormat
./gradlew detekt         # static analysis, zero-tolerance (config/detekt.yml)
./gradlew test           # unit + integration tests
./gradlew jacocoTestReport   # coverage report at build/reports/jacoco/test/html/index.html
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
- **Interface-first.** Domain defines the interface; `data/` implements it (`*I` suffix, e.g. `UserRepositoryI`).
- **Request DTOs implement `ValidatedRequest`** and provide `validate(): List<String>`. Never validate manually inside a
  handler — the `RequestValidation` plugin runs it for you.
- **Use the route helpers**, never inline body-limit/timeout/auth/rate-limit concerns:
  `validatedPost`/`validatedPut`/`validatedPatch`/`limitedPost` for body handling, `protectedApi { }` /
  `publicRateLimitedApi { }` for auth + rate limiting.
- **No wildcard imports.**
- **`runSuspendCatching`, not `runCatching`** — the latter swallows `CancellationException` and breaks coroutine
  cancellation.

## Security ground rules

Non-negotiable on every change — see the Security section of [CLAUDE.md](CLAUDE.md) for the full list. In short:
never store tokens in plain text, never log secrets/raw tokens/passwords, never leak internals in an error response,
secrets come from `AppConfig`/env only, and every new endpoint needs a deliberate rate-limit choice (`AUTH_LIMIT`,
`API_LIMIT`, or `UPLOAD_LIMIT` via `protectedApi`/`publicRateLimitedApi`).

## Commit & PR style

- Keep commits scoped to one logical change; write the message in the imperative ("Add X", not "Added X").
- If a change touches an existing architectural decision, add or update an ADR in `docs/adr/` rather than letting the
  rationale live only in the PR description.
- Update `CHANGELOG.md` for any user-visible or API-visible change (once it exists — see the project's open gaps if it
  doesn't yet).

## Recording a new architectural decision

Significant, hard-to-reverse choices (new dependency category, auth model change, data-store swap, etc.)
should get an ADR. Copy `docs/adr/0000-template.md`, number it sequentially, and fill it in as part of the same PR that
implements the decision — not after the fact.
