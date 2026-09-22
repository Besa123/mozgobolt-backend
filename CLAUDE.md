# CLAUDE.md

Idiomatic modern Kotlin, not Java-style. Clean Architecture, SOLID, DRY, YAGNI.

## Patterns

- Single `User` table with a `role` (`BUYER`/`VENDOR`) — no parallel auth system per role. A vendor's first
  registration creates a `Company` (invite-code join for others); a vendor links/delinks himself to a `Vehicle` for a
  shift, and GPS telemetry is always tied to the vehicle's currently active `VehicleAssignment`, never directly to a
  vendor. Role-gate a handler with `requireRole(UserRole.X)` (`feature/user/routing/RoleGuard.kt`) as its first line,
  after `protectedApi { }` — a reusable guard, not an ad-hoc per-handler check.
- `AppResult<T, E>` + `.fold()` for expected failures — never throw/catch for them.
- `domain/` defines the interface, `data/`/`service/` implement it — `feature/` impls suffixed `*I`
  (`UserServiceI`); `core/` uses descriptive names (`JwtTokenManager`) since multiple config-selected impls often share
  one interface there.
- Wrap every Exposed-touching call in `tx.transactional { }` (`TransactionalRunner`) — Exposed throws outside a
  transaction, invisible to unit-test fakes, only caught by a real DB (Docker-gated integration tests).
- Request DTOs implement `ValidatedRequest`; never validate manually in a handler.
- Body/timeout/validation only via the `RouteBuilders.kt` helpers (`validatedPost`/`Put`/`Patch`,
  `limitedPost`/`Delete`, `limitedFileUploadPost`/`Put`) — never raw Ktor `post`/`put`/`delete`; pick
  `BodyLimit`/`RequestTimeout` deliberately, never the default.
- Auth/rate-limit only via `protectedApi { }` / `publicRateLimitedApi { }` — never compose manually.
- `idempotent { }` on creation/mutation endpoints where a retry could duplicate state.
- `runSuspendCatching`, never `runCatching` (swallows `CancellationException`); a manual `catch (Exception)` must
  rethrow `CancellationException` first.
- Reuse stdlib/Ktor/Exposed over hand-rolled (e.g. `LikePattern.ofLiteral()`, not `.replace()`).
- External calls: `withXResilience { }` (`ResilienceConfig.kt`), never hand-rolled retry —
  [ADR 0005](docs/adr/0005-resilience4j-for-external-calls.md).
- A mutation to any synced entity calls `SyncService.recordChange(...)` in the same write transaction —
  [ADR 0006](docs/adr/0006-multi-device-sync-outbox-and-cursor-pull.md). This is for a user's own low-frequency CRUD
  mutations syncing across their own devices (`Company`/`Vehicle`/`VehicleAssignment`) — high-frequency GPS telemetry
  is deliberately exempt; it goes through the in-memory fast-path hub instead (`feature/vehicleTracking`).
- No wildcard imports.

## Security — non-negotiable

- Hash tokens (`tokenManager.hashTokenForStorage()`) before persisting; never store plaintext.
- Never log passwords, raw tokens, emails, or credentials — user IDs only. Every `logger.error` also reaches Sentry when
  configured, so this isn't just a local-file concern.
- Errors: always `ErrorResponse`, never leak internals (stack traces, DB errors).
- Secrets only from `AppConfig`/env vars — never hardcoded.
- Rate limits via `protectedApi`/`publicRateLimitedApi`: `AUTH_LIMIT` 5/min, `API_LIMIT` 60/min, `UPLOAD_LIMIT`
  10/min.
- Refresh-token reuse invalidates the whole family — do not simplify to single-token revocation
  ([ADR 0004](docs/adr/0004-refresh-token-family-rotation.md)).
