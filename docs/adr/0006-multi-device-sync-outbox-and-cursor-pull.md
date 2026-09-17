# 0006. Multi-device sync via transactional outbox + cursor pull + Postgres NOTIFY push hint

**Status:** Accepted

**Date:** 2026-08-28

## Context

One account, multiple devices (shared login, not separate identities). A change on one device must reach the others,
including one that was offline. Re-fetching everything on reconnect is correct but doesn't scale with pantry/history
size. Greenfield: no WebSocket/SSE, no broker, no `updated_at`/version columns on any pantry entity yet.

## Decision

`sync_events` outbox table (`user_id`, `entity_type`, `entity_id`, `operation`, monotonic `id` doubling as the cursor,
`occurred_at`). Every mutation to a synced entity inserts one row here in the *same* transaction as the write. New
`feature/sync/` exposes:

- **`GET /sync?since=<cursor>`** — pull-based catch-up, the single source of truth for every client. Returns pointers
  (`entityType`, `entityId`, `operation`) only, never entity data — the client re-fetches via that entity's own REST
  endpoint. Required adding `GET /pantry-entries/{id}`: pantry entries are cursor-paginated, so resolving a pointer for
  one not yet cached can't fall back to "call the list again" the way products/storage-locations can.
- **`GET /sync/live`** (SSE) — a liveness hint only, never a data channel: "there are events past cursor N,"
  client reacts by pulling `/sync?since=`. No unsubscribe endpoint — closing the connection is the unsubscribe. A 30s
  heartbeat detects dead sockets; connection lifetime is capped to the JWT's remaining validity (`withTimeoutOrNull`),
  with an `event: reconnect` sent ~60s before that cutoff so the client refreshes proactively instead of hitting a
  silent hard cutoff.

Fan-out uses Postgres `NOTIFY`, issued in the same transaction as the outbox insert. Each app instance holds one
`LISTEN` connection outside the Hikari pool and forwards matching notifications to that instance's open SSE sessions for
the relevant `user_id`.

**Self-echo suppression** (`/sync/live` only): `X-Device-Id` is threaded through to the `NOTIFY` payload so the device
that caused a mutation doesn't get a hint about its own action. Deliberately *not* applied to
`GET /sync` — a client's own cache can be lost (reinstall, corrupted storage) in a way the server has no way to know, so
excluding a device's own history from the pull would turn "this device already has it" into a hazard. Worst case of the
live-hint filter being wrong is one missed hint; the next pull always catches up regardless.

**Client contract**: call `GET /sync?since=` on every app foreground/resume and on every `/sync/live`
(re)connection — not only when a hint arrives. SSE is a latency optimization, never a substitute for the pull. A client
that treats SSE as sufficient on its own silently stops syncing the moment its connection dies without a clean close
(common on flaky networks/NAT timeouts).

No optimistic-lock/version column for v1 — conflicts resolve last-write-wins, ordered by `sync_events.id`.
`sync_events` is append-only, never pruned in v1.

## Alternatives considered

- **Kafka** — no throughput or multi-consumer need to justify the operational cost.
- **Redis pub/sub** — Postgres `NOTIFY` already fans out across multiple app instances; revisit only if NOTIFY's
  8000-byte payload/throughput limits become a real bottleneck.
- **Full-refresh sync on reconnect** — correct but unbounded cost as pantry/history grows.
- **WebSockets** — the channel is server→client only; SSE is plain HTTP and equally well-supported by Ktor.
- **Household/shared-tenant model** — out of scope; sync stays keyed on the existing `user_id`. Needs its own ADR if
  per-person attribution or roles ever become a real requirement.
- **Optimistic-lock version column** — deferred; no observed double-edit clobbering to justify the added complexity yet.
- **WAL-based CDC (Debezium, ElectricSQL/PowerSync)** — the more "automatic" version of this pattern (no
  `recordChange()` call to ever forget). Rejected: an abandoned/stalled replication slot grows Postgres's WAL
  unboundedly, a real risk without dedicated DB ops, and it commits to a client-side sync model before a client exists
  to make that call. Revisit if a second service needs the same change stream, or `recordChange()`
  omissions become a recurring real bug rather than a hypothetical one.

## Consequences

Purely additive — no changes to existing tables. Every synced feature's write path gets one mechanical addition:
`syncService.recordChange(userId, entityType, entityId, operation)` inside the existing write transaction.

New dependency: `ktor-server-sse`. New infra: a supervised `LISTEN` connection with reconnect/backoff
(`feature/sync/service/PostgresNotifyListener.kt`), feature-owned since no other feature needs it yet (YAGNI).

**Pruning `sync_events` later must ship with a `pruned_before_id` watermark and a `changesSince` check against it, in
the same change.** Deleting old rows without one lets a stale-enough client's cursor land inside the deleted range, and
the resulting empty/partial result is indistinguishable from "you're fully caught up" — silent data loss from the
client's perspective. A watermark with no reader is dead code; a delete with no reader-side check is a latent bug —
build both together.

Follow-up ADR required (not a silent change) if: a household/shared-tenant model gets built, or NOTIFY's limits force a
move to a broker.
