# 0001. Record architecture decisions

**Status:** Accepted

**Date:** 2026-08-25

## Context

ShelfLife is a solo-maintained project, but several non-obvious, hard-to-reverse decisions were already made before this
log existed (results-over-exceptions, feature-based layout, refresh-token family rotation). Without a record, the
rationale behind them lives only in commit messages and the maintainer's memory — which doesn't survive a break from the
project, a new contributor, or an AI assistant proposing a "simplification" that quietly removes load-bearing behavior.

## Decision

Use lightweight Architecture Decision Records, stored in `docs/adr/`, for every significant and hard-to-reverse decision
going forward. Existing major decisions are backfilled (0002–0004) rather than left undocumented.

## Alternatives considered

- **No formal record, rely on CLAUDE.md / commit history** — CLAUDE.md documents the *current* rule but not the
  *reasoning* or *rejected alternatives*; commit history is not searchable by decision and gets noisy fast.
- **Full MADR/Nygard tooling with generators** — unnecessary process overhead for a single-maintainer project at this
  stage.

## Consequences

Every ADR is a small amount of extra writing at decision time, in exchange for not re-litigating settled questions later
and not losing rationale when a decision's context has faded.
