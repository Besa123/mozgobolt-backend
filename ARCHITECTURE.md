# Architecture

ShelfLife is a Ktor backend organized around **feature-based Clean Architecture**: each business capability owns its
full vertical slice (domain → data → routing → DI), and cross-cutting concerns live in a shared `core/` module. This
document explains the shape of the code and why it's shaped that way. For the specific rules contributors (human or AI)
must follow, see [CLAUDE.md](CLAUDE.md). For the reasoning behind individual decisions, see [docs/adr/](docs/adr/).

## Layout

```
src/main/kotlin/
├── core/                     # shared infrastructure — no business logic
│   ├── data/                 # email, idempotency, security (JWT, password) implementations
│   ├── database/             # DataSource/HikariCP factory, transaction runner
│   ├── di/                   # Ktor DI wiring
│   ├── domain/                # AppResult, TokenManager/PasswordService interfaces, ValidatedRequest
│   ├── modules/               # AppConfig + one file per Ktor plugin (cors, rate-limit, status-pages, ...)
│   ├── routing/                # API versioning helper, shared ErrorResponse DTO
│   └── utility/functions/     # runSuspendCatching, protectedApi/publicRateLimitedApi
│
└── feature/<name>/            # one package per business capability
    ├── domain/                # models + repository/service *interfaces* — no framework types
    ├── data/                  # Exposed tables, mappers, repository implementations (*I suffix)
    ├── service/                # domain service implementations
    ├── routing/                # Ktor routes + request/response DTOs
    └── di/                     # wires interface -> implementation for this feature
```

`feature/user/` is the reference implementation of the full pattern (auth, registration, tokens).
`feature/health/` is intentionally minimal — it has no domain logic, so it only needs `routing/`.

## Why feature-based, not layer-based

A layer-based split (`controllers/`, `services/`, `repositories/` at the top level) scales badly once you have more than
a handful of endpoints: touching one feature means touching four unrelated top-level directories, and unrelated features
end up coupled through shared "services" packages. Feature-based layout keeps each capability cohesive and independently
deletable — removing a feature is (ideally)
deleting one directory, not hunting through the whole tree.

`core/` exists only for things that are genuinely cross-cutting (auth plumbing, DB access, config, Ktor plugin setup) —
it should never accumulate business logic. If something in `core/` starts making domain decisions, that's a sign it
belongs in a feature instead.

## Request lifecycle

1. **Ktor plugins** (`core/modules/plugin/`) run first, in the order configured in `Modules.kt`: default headers/CSP,
   CORS, call ID (request correlation), call logging, rate limiting, content negotiation, request validation, status
   pages.
2. **Routing** (`Routing.kt` → `core/routing/ApiVersion.kt`) groups all business routes under `/api/v1`. A breaking
   change gets its own `apiV2` block, added alongside — not a replacement — so existing clients keep working.
3. **Auth + rate limiting** are applied per route group via `protectedApi { }` (JWT-authenticated,
   `API_LIMIT` by default) or `publicRateLimitedApi { }` (unauthenticated, `AUTH_LIMIT` by default) — see
   `core/utility/functions/RouteUtilityFunctions.kt`. Routes never compose `authenticate`/`rateLimit`
   manually.
4. **Body handling** goes through `validatedPost`/`validatedPut`/`validatedPatch`/`limitedPost`
   (`core/modules/plugin/RouteBuilders.kt`), which enforce a body-size ceiling, a request timeout, and — for typed
   variants — automatic deserialization into a `ValidatedRequest` whose `validate()` result is checked by the
   `RequestValidation` plugin before the handler runs.
5. **Handlers** call into a feature's domain service, which returns `AppResult<T, E>` rather than throwing. The route
   folds that into an HTTP response; unexpected exceptions are caught by
   `StatusPages` and turned into a generic `ErrorResponse` — internals never leak to the client.
6. **Idempotency** (`core/data/idempotency/`) wraps creation/mutation endpoints where a retried request could otherwise
   create duplicate state.

## Core patterns

### `AppResult<T, E>` — results over exceptions

```kotlin
sealed class AppResult<out T, out E> {
    data class Success<out T>(val data: T) : AppResult<T, Nothing>()
    data class Error<out E>(val errorType: E) : AppResult<Nothing, E>()
}
```

Business logic returns this instead of throwing for *expected* failure modes (invalid credentials, not found, conflict,
etc.). Callers handle both branches explicitly via `.fold(onSuccess, onError)` — the compiler enforces that error
handling isn't forgotten. Exceptions are reserved for genuinely unexpected failures, which `StatusPages` catches at the
edge.

### Interface-first, `*I`-suffixed implementations

`domain/` defines what a feature needs (`UserRepository`, `UserService`) with zero framework dependencies. `data/`/
`service/` provide the implementation (`UserRepositoryI`, `UserServiceI`), wired together in `di/`. This keeps domain
logic testable without spinning up Ktor or a real database, and makes swapping an implementation (e.g. a repository
backed by a different store) a DI-wiring change, not a domain change.

### `ValidatedRequest` + route builders

Every request DTO implements `validate(): List<String>`. Combined with `validatedPost`/`validatedPut`/
`validatedPatch`, this means a handler never sees an invalid or oversized payload — validation, deserialization,
body-size limiting, and timeout enforcement all happen before the handler body runs, and none of it is duplicated
per-endpoint.

### Auth & rate limiting

Every endpoint gets a 150 req/min global baseline automatically. Named limits layer tighter control on top and are
chosen deliberately per endpoint, not defaulted:

| Limit          | Rate                     | Use                                                          |
|----------------|--------------------------|--------------------------------------------------------------|
| `AUTH_LIMIT`   | 5/min                    | Unauthenticated, sensitive (login, register, password reset) |
| `API_LIMIT`    | 60/min, keyed by user ID | Authenticated general API                                    |
| `UPLOAD_LIMIT` | 10/min                   | File uploads / heavy processing                              |

### Refresh-token family rotation

Refresh tokens belong to a "family." Each use rotates the token; reuse of an already-rotated token is treated as theft
and invalidates the *entire* family, not just the one token. This is deliberately not simplified to single-token
revocation — see [docs/adr/](docs/adr/) for the reasoning once recorded.

## Configuration

`AppConfig` (`core/modules/AppConfig.kt`) is a typed, `@Serializable` data class bound from
`application.conf` (HOCON) via Ktor's config/DI integration. Secrets are injected via `${?ENV_VAR}`
overrides — nothing is hardcoded. `.env` (via `dotenv-kotlin`) is a local-dev convenience only; it never overrides a
variable already present in the real environment, so CI/production env vars always win.

## Observability

- **Logging**: structured JSON (Logstash encoder) via Logback, async appenders, separate error stream, env-aware levels,
  rotation/retention configured (`src/main/resources/logback.xml`).
- **Request correlation**: the `callId` plugin attaches a request ID to every log line for a given request, standing in
  for full distributed tracing at the current scale.
- **Health**: `/health` checks DB connectivity; `/ready` reports readiness — see `feature/health/`.

Metrics (Micrometer/Prometheus) and distributed tracing (OpenTelemetry) are not yet in place — logging plus call-ID
correlation is the current substitute. See the project's known-gaps list before assuming either exists.

## Current known gaps

This document describes the architecture as built, not an aspirational end state. As of the last audit, notably missing:
containerization (Dockerfile/compose), a CD stage, OpenAPI/Swagger documentation, a coverage gate (Jacoco reports are
generated but not enforced), and circuit-breakers/retries around the one outbound integration (email). Don't assume any
of these exist without checking.
