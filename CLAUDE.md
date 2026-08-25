# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Mindset

Think like a senior backend developer. Follow Clean Architecture, SOLID, DRY, YAGNI. Apply design patterns where they
earn their place. Write idiomatic modern Kotlin — not Java-style Kotlin.

## Architecture

Feature-based layout: `feature/<name>/domain/`, `data/`, `routing/`, `di/`. Shared infrastructure in `core/`. New
features follow the same structure.

## Project Patterns

- **Results over exceptions:** business logic returns `AppResult<T, E>`; callers use `.fold(onSuccess, onError)`
- **Interface-first:** domain defines the interface; `data/` implements it (named `*I`: `UserServiceI`,
  `UserRepositoryI`)
- **Request DTOs implement `ValidatedRequest`:** provide `validate(): List<String>`; Ktor runs it automatically before
  the handler. Never validate manually inside a handler.
- **Route helpers:** `validatedPost`, `limitedPost`, `validatedPut`, `validatedPatch` in
  `core/modules/plugin/RouteBuilders.kt` — always use these, never inline body-size/timeout/validation concerns. Always
  pick the tightest fitting `BodyLimit` and `RequestTimeout` for the operation — never accept the default blindly:
    - `BodyLimit`: `TINY` (auth/simple), `SMALL` (tokens/short JSON), `MEDIUM` (rich JSON), `LARGE` (uploads)
    - `RequestTimeout`: `FAST` (auth/lookups), `STANDARD` (normal CRUD), `SLOW` (complex queries), `UPLOAD` (file
      processing)
- **Auth + rate-limit wrappers:** `protectedApi { }` and `publicRateLimitedApi { }` in `core/utility/functions/` —
  always use these to wrap routes, never manually compose `authenticate` + `rateLimit`
- **Error responses:** always use `ErrorResponse` from `core/routing/dto/response/` — never return inline maps or ad-hoc
  objects
- **Idempotency:** `idempotent { }` on creation/mutation endpoints where duplicates produce incorrect state; not on
  naturally idempotent or self-consistent operations
- **`runSuspendCatching` over `runCatching`:** `runCatching` swallows `CancellationException` and breaks coroutine
  cancellation; use `runSuspendCatching` from `core/utility/functions/`
- **External API calls use Resilience4j:** wrap with a `withXResilience { }` helper backed by a `Retry`/
  `CircuitBreaker` pair per dependency (`core/modules/plugin/ResilienceConfig.kt`), never a hand-rolled retry loop —
  see [ADR 0005](docs/adr/0005-resilience4j-for-external-calls.md)
- **No wildcard imports**

## Security

This is a backend — security is non-negotiable on every change.

- **Never store tokens in plain text.** Always hash with `tokenManager.hashTokenForStorage()` before persisting a
  refresh or verification token.
- **Never log sensitive fields** — passwords, raw tokens, full credentials. Log user IDs and sanitised context only.
- **Error responses must never leak internals.** Generic message via `ErrorResponse`, nothing else. Stack traces, DB
  errors, and internal state stay server-side.
- **Secrets come from `AppConfig` / env vars only.** Never hardcode credentials, keys, or salts.
- **Every endpoint has a global rate limit baseline** (150 req/min) applied automatically. Named limits add tighter
  control on top — always wrap routes with the appropriate one via `protectedApi { }` or `publicRateLimitedApi { }`:
    - `AUTH_LIMIT` — auth/unauthenticated sensitive ops (5 req/min)
    - `API_LIMIT` — authenticated general API (60 req/min, keyed by user ID)
    - `UPLOAD_LIMIT` — file uploads or heavy processing (10 req/min)
- **Preserve the refresh token family pattern.** Token reuse invalidates the entire family — this is intentional theft
  detection; do not simplify it away.
