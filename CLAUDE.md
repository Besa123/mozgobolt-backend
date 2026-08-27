# CLAUDE.md

Guidance for Claude Code (claude.ai/code) in this repository.

## Mindset

Think like a senior backend developer. Clean Architecture, SOLID, DRY, YAGNI. Design patterns where they earn their
place. Idiomatic modern Kotlin — not Java-style Kotlin.

## Architecture

Feature-based layout: `feature/<name>/domain/`, `data/`, `service/`, `routing/`, `di/`. Shared infrastructure in
`core/`. New features follow the same structure. `feature/product/` has non-obvious domain decisions — read
[docs/domain/pantry.md](docs/domain/pantry.md) before changing anything under it.

## Project Patterns

- **Results over exceptions:** business logic returns `AppResult<T, E>`; callers use `.fold(onSuccess, onError)`.
- **Interface-first:** domain defines the interface; `data/`/`service/` implement it. Feature implementations use the
  `*I` suffix (`UserServiceI`, `UserRepositoryI`).
- **Request DTOs implement `ValidatedRequest`** (`validate(): List<String>`); Ktor runs it before the handler. Never
  validate manually inside a handler.
- **Route helpers:** `validatedPost`, `limitedPost`, `validatedPut`, `validatedPatch`
  (`core/modules/plugin/RouteBuilders.kt`) — never inline body-size/timeout/validation concerns. Pick the tightest
  `BodyLimit` and `RequestTimeout` per operation, never the default blindly:
    - `BodyLimit`: `TINY` (auth/simple), `SMALL` (tokens/short JSON), `MEDIUM` (rich JSON), `LARGE` (uploads)
    - `RequestTimeout`: `FAST` (auth/lookups), `STANDARD` (normal CRUD), `SLOW` (complex queries), `UPLOAD` (file
      processing)
- **Auth + rate-limit wrappers:** `protectedApi { }` / `publicRateLimitedApi { }` (`core/utility/functions/`) — never
  compose `authenticate` + `rateLimit` manually.
- **Error responses:** always `ErrorResponse` (`core/routing/dto/response/`) — never inline maps or ad-hoc objects.
- **Idempotency:** `idempotent { }` on creation/mutation endpoints where duplicates produce incorrect state; not on
  naturally idempotent operations.
- **`runSuspendCatching` over `runCatching`:** `runCatching` swallows `CancellationException` and breaks coroutine
  cancellation; use `runSuspendCatching` (`core/utility/functions/`).
- **Reuse before you reinvent:** for a mechanical task (escaping, parsing, retrying, formatting, diffing, and the like),
  check whether it's already solved — Kotlin's stdlib, coroutines, Ktor, Exposed, or any other dependency already in the
  project — before writing a bespoke helper. A hand-rolled version re-derives something already tested and can silently
  miss an edge case the library already handles. (One instance of this: Exposed's
  `LikePattern.ofLiteral()` for LIKE-pattern escaping, instead of a hand-rolled `.replace()` chain.)
- **External API calls use Resilience4j:** wrap with a `withXResilience { }` helper backed by a `Retry`/
  `CircuitBreaker` pair per dependency (`core/modules/plugin/ResilienceConfig.kt`), never a hand-rolled retry loop —
  see [ADR 0005](docs/adr/0005-resilience4j-for-external-calls.md).
- **No wildcard imports.**

## Security

Non-negotiable on every change.

- **Never store tokens in plain text** — hash with `tokenManager.hashTokenForStorage()` before persisting.
- **Never log sensitive fields** (passwords, raw tokens, credentials) — user IDs and sanitised context only.
- **Error responses never leak internals** — generic `ErrorResponse` only; stack traces, DB errors, and internal state
  stay server-side.
- **Secrets come from `AppConfig`/env vars only** — never hardcode credentials, keys, or salts.
- **Rate limits:** a 150 req/min global baseline is automatic; wrap every route with the right named limit via
  `protectedApi { }` / `publicRateLimitedApi { }`:
    - `AUTH_LIMIT` — auth/unauthenticated sensitive ops (5 req/min)
    - `API_LIMIT` — authenticated general API (60 req/min, keyed by user ID)
    - `UPLOAD_LIMIT` — file uploads or heavy processing (10 req/min)
- **Preserve the refresh-token family pattern** — reuse invalidates the entire family; intentional theft detection, do
  not simplify away ([ADR 0004](docs/adr/0004-refresh-token-family-rotation.md)).
