# ADR 0010: UTC policy for timezone-less timestamp storage

**Status:** Accepted for containerized runtimes; historical schema conversion deferred

## Context

The current Exposed Java-time `timestamp()` mapping represents `java.time.Instant` using PostgreSQL `TIMESTAMP WITHOUT
TIME ZONE`. Its round-trip behavior depends on the JVM timezone. A load-test seeder running on a non-UTC host and an app
running in a UTC container can therefore interpret the same persisted `started_at` wall time differently; telemetry
may appear to predate its assignment.

## Decision

Containerized app runtimes use UTC, and test seeders targeting them use the same JVM timezone. Do not convert existing
timestamp columns to `TIMESTAMPTZ` until the timezone used to write deployed historical rows is known and a reviewed
data-preserving migration can specify that legacy zone explicitly.

## Consequences

New containerized writers and readers agree on one wall-clock representation, and the load-test instructions pin both
sides to UTC. Direct host runs that share a database must use the same timezone as the other application processes.
This does not retroactively repair data written under a different timezone; that requires deployment-history evidence
before migration.
