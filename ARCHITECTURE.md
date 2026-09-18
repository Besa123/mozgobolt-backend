# Architecture

Ktor backend, feature-based Clean Architecture: `core/` (cross-cutting infra only, no business logic) +
`feature/<name>/{domain,data,service,routing,di}` per capability. Contributor rules: [CLAUDE.md](CLAUDE.md). Decision
rationale: [docs/adr/](docs/adr/). Pantry domain specifics: [docs/domain/pantry.md](docs/domain/pantry.md).

## Layout notes not obvious from the folders

- `feature/user/` is the reference implementation of the full pattern; `feature/health/` is intentionally
  `routing/`-only (no domain logic).
- One exception to the per-feature layout: `product`/`storageLocation`/`quantityUnit`/`pantryEntry`'s Exposed tables all
  live in `feature/product/data/database/` (FK/DAO relations) — see pantry.md decision 12.
- Rationale for feature-based over layer-based: [ADR 0002](docs/adr/0002-feature-based-clean-architecture.md).

## Request lifecycle

1. Ktor plugins (`core/modules/plugin/`) install in the order in `Modules.kt` — headers → JWT auth → CORS → content
   negotiation → rate limiting → call ID → validation → status pages. Don't reorder without checking why each one sits
   where it does.
2. Routing groups everything under `/api/v1` (`core/routing/ApiVersion.kt`); a breaking change gets `/api/v2`
   added alongside, never a replacement.
3. Auth, rate limiting, body-size limits, timeouts, and validation are enforced by the route-builder wrappers (see
   CLAUDE.md) before a handler runs — never composed manually per route.
4. Handlers call a domain service returning `AppResult<T, E>` (rationale:
   [ADR 0003](docs/adr/0003-results-over-exceptions-for-business-logic.md)); unexpected exceptions are caught by
   `StatusPages` into a generic `ErrorResponse`.
5. `core/data/idempotency/` wraps creation/mutation endpoints where a retry could otherwise duplicate state.

Caveat: body-size limits are enforced via `Content-Length` — a chunked request without one bypasses them (file uploads
use a streaming byte-count cap instead — see
[ADR 0007](docs/adr/0007-pantry-entry-image-storage-and-sanitization.md)).

## Load-bearing decisions — don't simplify these away without reading the ADR

- Refresh-token family rotation: reuse invalidates the whole family, not just one token —
  [ADR 0004](docs/adr/0004-refresh-token-family-rotation.md).
- Resilience4j circuit-breaker wraps retry, not the reverse; retry fires only on transient email errors —
  [ADR 0005](docs/adr/0005-resilience4j-for-external-calls.md).
- Multi-device sync: pull (`GET /sync?since=`) is the only source of truth; SSE push is a liveness hint, never required
  for correctness — [ADR 0006](docs/adr/0006-multi-device-sync-outbox-and-cursor-pull.md).
- Image uploads are unconditionally re-encoded from decoded pixels — the primary defense, not ClamAV (optional, off by
  default) — [ADR 0007](docs/adr/0007-pantry-entry-image-storage-and-sanitization.md).

## Configuration

`AppConfig` (`core/modules/AppConfig.kt`) is typed, bound from `application.conf` (HOCON); secrets come only from
`${?ENV_VAR}` overrides. `.env` (dotenv-kotlin) is local-dev only — never overrides a real env var.

## Observability

Structured JSON logs (Logback/Logstash), request-correlated via `callId`. `/health` checks DB connectivity,
`/ready` reports readiness (`feature/health/`). Error tracking: every `logger.error` becomes a Sentry issue when
`SENTRY_DSN` is set (`core/modules/plugin/SentryConfig.kt`) — no-op, no code changes needed, when unset. No metrics
(Micrometer/Prometheus) or distributed tracing yet.

## Known gaps

No containerization, no CD stage, no metrics/tracing, no coverage gate (Jacoco reports but doesn't enforce). Verify
against the code before assuming anything else exists.
