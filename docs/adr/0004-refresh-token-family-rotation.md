# 0004. Refresh-token family rotation with theft detection

**Status:** Accepted

**Date:** 2026-08-25 (backfilled — reflects the refresh/logout token flow, in place since early commits)

## Context

Refresh tokens are long-lived. If one is exfiltrated (compromised device, leaked log, DB read), a system that only
rotates the single token in use lets an attacker use it quietly, with no penalty and no signal to the legitimate user
that theft occurred.

## Decision

Refresh tokens belong to a "family," established at login. Every use rotates the token and invalidates the previous one.
Presenting an already-rotated token is treated as evidence of theft: the *entire family* is invalidated immediately,
forcing re-authentication. Only token hashes (`tokenManager.hashTokenForStorage()`) are persisted.

## Alternatives considered

- **Single-token revocation on reuse** — if the stolen token is used first, the attacker gets a valid rotation and the
  legitimate user is merely logged out, with no theft signal and the session lineage left alive.
- **Static long-lived refresh token, no rotation** — a leaked token stays valid its whole lifetime, undetected.
- **Short-lived refresh tokens, no family tracking** — shrinks the exposure window but can't distinguish natural expiry
  from replay, so it limits theft rather than detecting it.

## Consequences

Do *not* simplify this to single-token revocation — dropping family-wide invalidation as "unnecessary complexity"
removes the theft-detection property the design exists for. Costs: per-family state to track and more nuanced tests
(reuse-after-rotation must invalidate the whole family, not just fail one token check).
