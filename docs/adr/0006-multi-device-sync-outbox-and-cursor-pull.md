# ADR 0006: Durable sync outbox and cursor pull

**Status:** Accepted (retrospectively documented)

## Context

Users can update synced data on multiple devices, while live push delivery can be interrupted or duplicated. A volatile
notification channel cannot be the only way a client learns about durable changes.

## Decision

Record each low-frequency synced mutation in the durable sync store in the same write transaction. Clients pull changes
using a cursor; SSE carries liveness hints and is not the source of truth.

## Consequences

Clients recover after missed or duplicate push events by pulling from their last cursor. PostgreSQL remains the
authoritative source, while in-memory hubs and Valkey Pub/Sub remain best-effort delivery mechanisms.
