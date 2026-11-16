# CLAUDE.md

Idiomatic modern Kotlin, not Java-style. Clean Architecture, SOLID, DRY, YAGNI.

## Patterns

- For Exposed persistence work, load `.github/skills/exposed/SKILL.md` and its task-relevant references before implementing.
- The current API has one `User` table and one account `role` (`BUYER`/`VENDOR`); it has no role-switch/add-role
  endpoint, although the product brief intends buyer/vendor modes on one account. Do not imply dual-mode support until
  implemented. Registration creates only the user; vendors create or join a `Company` afterward. A vendor links or
  unlinks himself to a `Vehicle` for a shift. Telemetry batches derive the vehicle and assignment from the active
  assignment at ingestion; persisted rows also retain a vendor ID. Role-gate a handler with `requireRole(UserRole.X)`
  (`feature/user/routing/RoleGuard.kt`) as its first line, after `protectedApi { }` — a reusable guard, not an ad-hoc
  per-handler check.
- Nearby lookups use H3 cells as candidate buckets. H3 `gridDisk` measures cell hops, not a geodesic radius; cell-only
  filtering can include points outside the requested radius. Do not promise an exact radius unless a final geographic
  distance check is applied.
- Live-map subscribers keep pending events conflated per vehicle (`InMemoryVehicleLocationHub.PendingLiveEvents`): a
  slow client may skip intermediate positions but must always receive each vehicle's latest state, including
  `Offline`. Never replace this with a fixed FIFO buffer that drops the oldest event: a dropped `Offline` leaves a ghost
  pin until reconnect. `./gradlew runLiveLoadTest` (see `load-tests/README.md`) checks map convergence under load.
- CPU-bound work (password hashing, image processing) shares `Dispatchers.Default` with request handling and SSE
  fan-out, so its concurrency defaults to half the cores; a login burst at full-core concurrency stalled telemetry and
  live delivery for seconds in the load test.
- Every `GET .../live` SSE route (vehicle-tracking, sync, vehicle-ping) runs its collection loop via
  `runSseCollectionWithReconnectWarning` (`core/modules/plugin/SseConventions.kt`), which cancels the reconnect-warning
  timer once the loop returns. Never schedule that timer with a bare `launch { }` beside the loop again: an uncancelled
  sibling coroutine keeps the handler's scope — and the connection — open for up to `RECONNECT_WARNING_LEAD_TIME`
  before the token's own expiry, even after `collectWhileAccessSessionActive` has already detected a revoked session
  and ended the loop. A security probe suite found a revoked buyer session still receiving live vehicle locations
  (verified against the real app: the connection stayed open well past the 30s revocation recheck, closing only once
  manually traced to this cause). `SseConventionsTest` is the regression guard.
- Free-text fields validated with `validateDisplayName` (company/vehicle names, saved-location labels, display names)
  reject control characters including NUL, not just markup (`<>"'&;`). Postgres `text`/`varchar` cannot store an
  embedded NUL at all; without this check it reaches the database and surfaces as an uncaught 500 instead of a clean
  400 validation error.
- `AppResult<T, E>` combinators (`fold`, `map`, `flatMap`) for expected failures — never throw/catch for them.
- `domain/` defines the interface, `data/`/`service/` implement it — `feature/` impls suffixed `*I`
  (`UserServiceI`); `core/` uses descriptive names (`JwtTokenManager`) since multiple config-selected impls often share
  one interface there.
- Run feature service/repository Exposed work through `tx.transactional { }` (`TransactionalRunner`); explicit
  infrastructure transaction owners such as `ExposedIdempotencyStore` are exceptions and must still keep all Exposed
  work inside their transaction. Exposed calls outside a transaction may be invisible to unit-test fakes, so verify
  database behavior with the Docker-gated PostgreSQL tests.
- Request DTOs implement `ValidatedRequest`; never validate manually in a handler.
- Body/timeout/validation only via the `RouteBuilders.kt` helpers (`validatedPost`/`Put`/`Patch`,
  `limitedPost`/`Delete`, `limitedFileUploadPost`/`Put`) — never raw Ktor `post`/`put`/`delete`; pick
  `BodyLimit`/`RequestTimeout` deliberately, never the default.
- Auth/rate-limit only via `protectedApi { }` / `publicRateLimitedApi { }` — never compose manually.
- `idempotent { }` on retry-sensitive mutations, and send an `Idempotency-Key` when relying on deduplication; without
  that header the helper executes the handler without storing a result.
- Use `runSuspendCatching` for suspend exception boundaries; it returns `AppResult`, checks caller cancellation
  before and after the block, and propagates `CancellationException` and fatal `Error`s. Use `runSyncCatching` only for
  synchronous boundaries. Use `runWhileActive` when a completed value must not escape after cancellation. Plain Kotlin
  `runCatching` is appropriate for synchronous parsing. A manual `catch (Exception)` must rethrow
  `CancellationException` first.
- Keep exception-capture wrappers non-inline; inline the small pure `AppResult` combinators. Use `crossinline` only
  when an inline lambda crosses a restricted execution boundary, and `noinline` only when a callback must be retained
  or passed as a value.
- Reuse stdlib/Ktor/Exposed over hand-rolled (e.g. `LikePattern.ofLiteral()`, not `.replace()`).
- External calls: use `ResiliencePolicy.execute { }` and the existing profiles under `core/resilience/`; never add
  hand-rolled retry.
- A mutation to any synced entity calls `SyncService.recordChange(...)` in the same write transaction
  (`feature/sync/domain/SyncService.kt`). This covers the user's low-frequency CRUD changes syncing across devices;
  high-frequency GPS telemetry is deliberately exempt and goes through the in-memory fast-path hub instead
  (`feature/vehicleTracking`).
- No wildcard imports.

## Backend guarantees — apply when relevant

When changing persisted state, retries, external effects, authorization, or multi-instance behavior, identify the
invariant and failure boundary before coding. Apply the relevant guarantees below; do not add architecture or
concurrency merely to use an acronym. In the handoff, state what invariant is protected and which tests or measurements
verify it.

- **ACID / transaction integrity:** keep one logical database mutation and its related sync/outbox records in one
  `tx.transactional { }`. Returning `AppResult.Error` does not roll back; validate before writes or use an explicit
  rollback-capable design. Enforce concurrent invariants with PostgreSQL constraints, locks, or conditional writes,
  not read-then-write checks alone. Keep operations on one Exposed transaction sequential, and do not hold its
  connection/locks across network calls.
- **Retries / idempotency / delivery:** use `idempotent { }` for retryable mutations where duplicate execution can
  change state. A database commit and a remote service call are not one ACID transaction: use the existing outbox or
  an explicit after-commit best-effort action, and define duplicate/retry behavior. Do not claim exactly-once delivery.
- **Distributed consistency / CAP:** PostgreSQL is the source of truth for durable state. In-memory hubs and Redis
  Pub/Sub are volatile delivery mechanisms; sync SSE is a hint and cursor-based pull is authoritative. When changing
  multi-instance behavior, specify partition, reconnect, duplicate, and recovery semantics; never treat Pub/Sub as a
  durable log.
- **Security / OWASP:** preserve both route-level role checks and resource/company authorization. Validate request DTOs,
  bind SQL values, use standard auth/rate-limit helpers, and keep secrets and sensitive values out of logs. Add tests
  for unauthorized and cross-owner access paths when touching a trust boundary.
- **Reliability / capacity:** preserve cancellation, bounded queues, timeouts, and existing Resilience4j policies.
  Do not make throughput or availability claims from formulas alone; load-test the affected path and record relevant
  latency, error, CPU/memory, and dependency-pool evidence. Define SLI/SLO/SLA or RTO/RPO targets only when product
  requirements supply them; do not invent targets or universal pool sizes.
- **Evidence:** database behavior and concurrency claims require the real-PostgreSQL integration tests when available;
  fakes alone do not prove transaction, constraint, or isolation behavior. Check skipped-test counts and distinguish
  tests from load tests.

## Security — non-negotiable

- Hash tokens (`tokenManager.hashTokenForStorage()`) before persisting; never store plaintext.
- Never log passwords, raw tokens, emails, or credentials — user IDs only. Every `logger.error` also reaches Sentry when
  configured, so this isn't just a local-file concern.
- Errors: always `ErrorResponse`, never leak internals (stack traces, DB errors).
- Secrets only from `AppConfig`/env vars — never hardcoded.
- Rate limits via `protectedApi`/`publicRateLimitedApi`: global 150/min per trusted source IP, `AUTH_LIMIT` 5/min per
  source IP, `API_LIMIT` 60/min per authenticated user (source-IP fallback), and `UPLOAD_LIMIT` 10/min per source IP.
- Refresh-token reuse invalidates the whole family — do not simplify to single-token revocation
  (`feature/user/service/UserServiceI.kt`).
