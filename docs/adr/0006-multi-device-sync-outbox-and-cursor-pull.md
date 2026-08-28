# 0006. Multi-device sync via transactional outbox + cursor pull + Postgres NOTIFY push hint

**Status:** Accepted

**Date:** 2026-08-28

## Context

A single account is used from multiple devices (shared login, not separate per-person identities). A change made on one
device — renaming a storage location, adding/editing a pantry entry — needs to reach the other devices, including one
that was offline and has since reconnected. Re-fetching everything on every reconnect is correct but doesn't scale with
pantry/history size and is explicitly what we want to avoid.

Today: single Ktor instance, no WebSocket/SSE plugin, no message broker, no Redis, and no `updated_at`/version columns
on any pantry entity (`feature/product`, `feature/storageLocation`, `feature/pantryEntry`) — this is greenfield, not a
retrofit.

## Decision

Add a transactional outbox table, `sync_events` (`user_id`, `entity_type`, `entity_id`, `operation`
[`upsert`/`delete`], monotonic `id` bigserial doubling as the sync cursor, `occurred_at`). Every mutation to a synced
entity inserts one row here in the *same* database transaction as the write — no separate dual-write step, no
eventual-consistency gap between "the pantry entry changed" and "we recorded that it changed."

New `feature/sync/` (`domain/`, `data/`, `service/`, `routing/`, `di/`, same shape as every other feature per
[ADR 0002](0002-feature-based-clean-architecture.md)) exposes:

- **`GET /sync?since=<cursor>`** — pull-based catch-up. Returns every `sync_events` row for the caller's `user_id`
  above the given cursor as a pointer (`entityType`, `entityId`, `operation`) — never the entity's data itself. The
  client re-fetches whichever entity/collection changed via that entity's own existing REST endpoint. This keeps
  `feature/sync` a genuine leaf feature with zero coupling to `product`/`storageLocation`/`pantryEntry` domain code (no
  joins, no snapshotting, single responsibility: an event log), at the cost of one extra round trip the client would
  need to make anyway to get real data. This is the single source of truth for every client, whether it's polling, just
  reconnected, or reacting to a push hint.

  "Its own existing REST endpoint" needed one addition to actually be true: `products` and `storage_locations` list
  endpoints already return everything unpaginated, so a pointer for either just means "call that list again" — but
  `pantry_entries` is cursor-paginated (`docs/domain/pantry.md` decision 11), and there was no way to fetch a single
  entry by id. Without one, a pointer for an entry not yet in a device's local cache could only be resolved by
  re-walking the entire paginated list — exactly the "grab everything" cost this design exists to avoid. Added
  `GET /pantry-entries/{id}` for this specifically; `products`/`storage_locations` don't need an equivalent one.
- **`GET /sync/live`** (SSE, via Ktor's official `ktor-server-sse` plugin) — a live hint, not a data channel. Tells
  connected devices "there are events past cursor N"; the client reacts by calling `/sync?since=` again. The channel is
  one-directional (server → client), so SSE over plain HTTP is used instead of WebSockets — nothing here needs the
  client to send anything back over this connection.

  There is deliberately no "unsubscribe" endpoint — closing the connection is the only unsubscribe there is, and a
  cancelled collector is dropped by `MutableSharedFlow` automatically; nothing is left registered server-side to leak.
  The one real risk is the mirror case: a connection that goes silently dead (network vanishes, no clean TCP close)
  with no real event to push in the meantime would otherwise sit "open" on the server indefinitely, since nothing ever
  attempts to write to it. Two mitigations, both closing real gaps rather than defense-in-depth for its own sake:
    - **Heartbeat** (`ServerSSESession.heartbeat { period = 30s }`, Ktor's own built-in mechanism): a periodic write
      that fails the moment the socket is actually dead, tearing the session down. Detection latency still depends on
      the OS/TCP stack noticing the peer is gone, not purely on this period.
    - **Token-bounded connection lifetime**: the JWT is checked once, at connection open, same as any request — but
      unlike a normal request, a long-lived stream doesn't get re-checked per message. Without a cap, a connection
      opened with a token that later expires would keep streaming past that boundary. The connection's own lifetime is
      capped to the token's remaining validity (`withTimeoutOrNull`), forcing a reconnect with a fresh token — the same
      boundary every other authenticated endpoint already has. The payload itself (a bare event id, never entity data)
      makes the actual exposure from this low-severity even before the fix; the fix is about not leaving an
      authorization check with unbounded validity on principle, not about a concrete leak found.

  **This makes `/sync/live` a recurring, not one-shot, connection.** Access tokens are short-lived (15 minutes as of
  writing — `JwtTokenManager`), so the cap above means every client reconnects roughly every 15 minutes as a normal part
  of this endpoint's lifecycle, not an edge case. A stream ending has no distinct error to tell the client *why*
  (token expiry and a genuinely dead connection both just end the stream the same way), so the server sends an explicit
  `event: reconnect` SSE event ~60s before the token-expiry cutoff — a distinct, deliberate signal to refresh the token
  and reconnect proactively, with the hard cutoff still there as the backstop if the client doesn't act on it. Client
  contract, extended: treat `reconnect` the same as any other disconnect — refresh the token if needed, reconnect, and
  pull `/sync?since=` regardless of whether anything was missed.

Fan-out from the request that wrote the outbox row to the open SSE connections uses Postgres `NOTIFY`, issued in the
same transaction as the outbox insert (Postgres delivers queued `NOTIFY`s atomically at commit). Each app instance holds
one long-lived `LISTEN` connection (outside the HikariCP pool) and forwards matching notifications to that instance's
open SSE sessions for the relevant `user_id`.

**Self-echo suppression**: the device that caused a mutation is also, normally, subscribed to its own `/sync/live`
connection — without this, it would receive a hint about its own action and dutifully re-pull data it already has
straight from the mutating request's own response. The `X-Device-Id` header (already read for log correlation) is
threaded through, the same way `userId` already is, from each mutating route → its feature service →
`SyncService.recordChange` → `SyncRepository`, which encodes it as a third, *never-persisted* field in the `NOTIFY`
payload (`"<userId>:<eventId>:<originDeviceId>"`, parsed with `split(":", limit = 3)` so the device id, as the last
field, keeps any colons of its own intact). `PostgresNotifyListener` forwards it into `SyncEventHub.publish`; the hub
still broadcasts uniformly to every subscriber of a `userId` — filtering is each subscriber's own decision
(`SyncHint.isOwnAction`), made in `/sync/live`'s handler by comparing against its own connection's device id, not
something baked into hub delivery. A client that never sends a device id is completely unaffected — it gets every hint,
exactly as before this existed.

This is deliberately *never* applied to `GET /sync` — only to the live hint. A client's own local cache can drift or be
lost entirely (reinstall, corrupted storage) in a way the server has no way to know about; excluding a device's own
history from the pull would turn "this device already has it" into a hazard the moment that assumption stops holding,
breaking the guarantee `GET /sync` exists to provide. The live hint carries no such risk precisely because it was never
authoritative to begin with — worst case of a wrong filtering decision here is one missed hint, and the next pull (on
reconnect, on foreground, or on the next real change) always catches up regardless.

Because the push channel is a hint only, a missed `NOTIFY` (dropped listener, app restart, client offline) is harmless —
the client's next `/sync?since=` call catches up regardless. Correctness never depends on push delivery.

**Client contract** (this is the part that makes the above true, not just convenient): a client must call
`GET /sync?since=` on every app foreground/resume and on every `/sync/live` (re)connection — not only when a hint
arrives. SSE is a latency optimization on top of that baseline, never a replacement for it. A client that treats SSE as
sufficient on its own will silently stop syncing the moment its connection dies without a clean close event (a NAT/proxy
timeout dropping an idle connection, for instance, is common and gives no signal to either side). Multiple devices need
no special handling beyond this: the hub is keyed by `user_id`, not by device, and the cursor is entirely client-owned —
the server never tracks which device is at which point, so N devices for one account behave identically to one.

No optimistic-lock/version column for v1. Conflicts resolve as last-write-wins, ordered by `sync_events.id`/
`occurred_at` — acceptable for casual concurrent edits on a shared pantry; revisit only if real double-edit clobbering
shows up in practice.

## Alternatives considered

- **Kafka** — rejected. Earns its cost with high throughput or multiple independent downstream consumer services;
  neither applies here. The operational cost (cluster/managed service, consumer-group and offset management, schema
  registry) buys nothing this app currently needs. Revisit only if a second, independent service needs to consume the
  same change stream.
- **Redis pub/sub for push fan-out** — rejected for now, not just deferred for later: Postgres `LISTEN`/`NOTIFY`
  already fans out correctly across *multiple* app instances (each instance holds its own `LISTEN` connection to the
  same Postgres backend, so this isn't a single-instance-only shortcut), so Redis would add an operational dependency
  without solving a problem NOTIFY doesn't already solve at this scale. Revisit only if NOTIFY's throughput/payload
  limits (8000-byte payload, not built for high message rates) become a real bottleneck.
- **Full-refresh sync on reconnect** — rejected. Trivially correct, but cost grows unbounded with pantry/history size;
  exactly the "feels cheap" approach we're avoiding.
- **WebSockets instead of SSE** — rejected. The channel is server → client only; bidirectional framing buys nothing, and
  SSE is plain HTTP (simpler to proxy, log, and reason about) with an equally official Ktor plugin.
- **Household/shared-tenant model** (separate identities sharing one pantry) — considered and explicitly deferred, not
  built. The actual requirement is multiple devices sharing one literal login, so sync stays scoped to the existing
  `user_id`; no new membership/invite concept is introduced. Revisit as its own ADR if per-person attribution or roles
  become a real requirement — that decision should not be smuggled in as a side effect of this one.
- **Optimistic-lock version column** — considered, deferred. Adds a real column and 409-handling for a conflict pattern
  (concurrent edits within seconds, from the same account) that hasn't been shown to happen. Last-write-wins is simpler
  and reversible; adding version columns later is additive, not a migration off of something wrong.
- **WAL-based CDC (Debezium embedded, or a purpose-built sync product like ElectricSQL/PowerSync/Supabase Realtime)**
  — this is the more "automatic" version of the outbox pattern: tail Postgres's logical replication stream instead of
  writing an outbox row in application code, so no service method can ever forget to record a change. Rejected for this
  project, not because it's not modern — it's the standard approach CDC-heavy systems use — but because here it trades a
  code-review-catchable risk (a developer forgets `recordChange()`) for an operationally dangerous one: an
  abandoned/unconsumed replication slot grows Postgres's WAL without bound if the consumer ever crashes or falls behind,
  which is a real risk for a small team without dedicated DB operations. It also requires enabling logical replication
  on the database and commits to a specific client-side sync/materialization model (e.g. local SQLite,
  "shapes") before there is even a client repo to make that call for. Revisit if/when: a second independent service
  needs the same change stream, mutation call-sites become numerous enough that `recordChange()` omissions become a
  recurring real bug (not hypothetical), or an actual mobile/offline client is being built and its sync model is chosen
  deliberately rather than inherited from an infra pick.

## Consequences

Purely additive: one new table, no changes to existing entity tables/migrations, no new columns to backfill. Each
existing feature's service layer gets one small, mechanical addition — call `syncService.recordChange(userId,
entityType, entityId, operation)` inside its existing write transaction, immediately after the write.

New dependency: `ktor-server-sse` (add to `gradle/libs.versions.toml` / `build.gradle.kts`), plus a new
`core/modules/plugin/SseConfig.kt` registration, following the existing plugin-config pattern.

One genuinely new infrastructural piece: a supervised Postgres `LISTEN` connection with reconnect/backoff, held outside
the Hikari pool (`feature/sync/service/PostgresNotifyListener.kt`) — feature-owned rather than `core/`, since no other
feature needs it; if a second feature ever does, that's the trigger to promote it, not before (YAGNI).

`sync_events` is append-only and never pruned in v1 — deliberate, not an oversight, but not urgent either. Rough sizing:
a genuinely heavy user generating ~10 mutations/day is ~3,650 rows/year; at ~100–150 bytes/row all-in (row + the
`(user_id, id)` index), that's under 1 MB/year *per user*. Even 10,000 such users is a few GB/year — not a real problem
on any timescale worth planning around today. Revisit when it's an actual line item in a storage/backup bill, not
preemptively.

When it is revisited, pruning must not ship alone — deleting old rows without a companion safety check silently breaks
any client whose cursor predates what was deleted: `WHERE id > cursor` can't distinguish "you're fully caught up" from
"the history you needed was already deleted," so a stale-enough client would believe it's synced while actually missing
real changes. The correct shape: track a `pruned_before_id` watermark (a single persisted value, updated by whatever job
does the pruning) and have `changesSince` check `cursor < pruned_before_id` before trusting an empty/partial result —
surfacing a distinct "cursor too old, do a full resync" outcome instead of a normal page when it trips. Build the
watermark check and the pruning job in the same change; a `pruned_before_id` with no reader is dead code, and a delete
statement with no reader-side check is a latent data-loss bug.

Follow-up ADR required, not a silent change, if either of these becomes real: (a) a household/shared-tenant model, or
(b) NOTIFY throughput/payload limits force a move to Redis or another broker. Skipping optimistic locking is a
deliberate v1 scope decision, not an oversight — a future contributor adding a version column should update this ADR
rather than treat its absence as a bug.
