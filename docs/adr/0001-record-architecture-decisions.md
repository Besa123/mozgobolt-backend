# 0001. Record architecture decisions

**Status:** Accepted

**Date:** 2026-08-25

## Context

Several non-obvious, hard-to-reverse decisions predate this log (results-over-exceptions, feature-based layout,
refresh-token family rotation). Without a record, their rationale lives only in commit messages and the maintainer's
memory — which doesn't survive a break from the project, a new contributor, or an AI assistant proposing a
"simplification" that removes load-bearing behavior.

## Decision

Lightweight ADRs in `docs/adr/` for every significant, hard-to-reverse decision. Existing major decisions are backfilled
(0002–0004) rather than left undocumented.

## Alternatives considered

- **No formal record (CLAUDE.md + commit history)** — CLAUDE.md documents the current rule, not the reasoning or
  rejected alternatives; commit history isn't searchable by decision.
- **Full MADR/Nygard tooling with generators** — process overhead a single-maintainer project doesn't need.

## Consequences

A small amount of writing at decision time, in exchange for not re-litigating settled questions or losing rationale
after context fades.
