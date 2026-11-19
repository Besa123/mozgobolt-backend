# ADR 0004: Refresh-token family rotation

**Status:** Accepted (retrospectively documented)

## Context

A refresh token can be copied and replayed. Revoking only the presented token after reuse would leave a legitimately
rotated replacement from the same compromised session usable.

## Decision

Refresh tokens rotate, are hashed before persistence, and are associated with a token family. Reuse of a rotated token
invalidates the entire family, including its replacement tokens.

## Consequences

Clients must replace refresh tokens after successful refresh and reauthenticate after a reuse detection. The family
invariant is enforced by `UserServiceI` and covered by service, route, and security-probe tests.
