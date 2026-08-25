# 0004. Refresh-token family rotation with theft detection

**Status:** Accepted

**Date:** 2026-08-25 (backfilled — reflects the refresh/logout token flow, in place since early commits)

## Context

Refresh tokens are long-lived and, if a stored/hashed token is ever exfiltrated (compromised device, leaked log, DB
read), a system that only rotates the single token in use gives an attacker no penalty for using a stolen token quietly,
and gives the legitimate user no way to know a theft happened.

## Decision

Refresh tokens belong to a "family," established at login. Every use of a refresh token rotates it to a new one in the
same family, and the previous token is invalidated. If an already-rotated (i.e. previously used) token is presented
again, that is treated as evidence of theft — the *entire family* is invalidated immediately, forcing re-authentication,
not just the one reused token. Tokens are never stored in plain text; only their hash (via
`tokenManager.hashTokenForStorage()`) is persisted.

## Alternatives considered

- **Single-token revocation on reuse** — simpler, but if a stolen token is used before the legitimate user's next
  request, the attacker gets one valid rotation and the legitimate user is merely logged out with no signal that theft
  occurred, rather than the whole session lineage being cut off immediately.
- **No rotation, static long-lived refresh token** — minimal implementation, but a single leaked token stays valid for
  its entire lifetime with no detection mechanism at all.
- **Short-lived refresh tokens with no family tracking** — reduces the exposure window but still can't distinguish "the
  legitimate user's own natural token expiry" from "someone replayed a stolen token,"
  so it can't react to theft, only limit its duration.

## Consequences

This is intentionally *not* simplified to single-token revocation — a future contributor (or an AI assistant) proposing
to drop family-wide invalidation as "unnecessary complexity" would be removing the theft-detection property this design
exists for. The cost is more state to track per family (current token, prior-token lineage) and more nuanced tests
(reuse-after-rotation must invalidate the whole family, not just fail one token check).
