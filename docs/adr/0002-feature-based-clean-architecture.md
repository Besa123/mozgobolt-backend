# 0002. Feature-based Clean Architecture layout

**Status:** Accepted

**Date:** 2026-08-25 (backfilled — reflects the layout in place since the project's early commits)

## Context

The codebase needed a structure that scales past a handful of endpoints without every feature becoming entangled with
every other feature through shared top-level layers, while still keeping domain logic free of framework concerns (Ktor,
Exposed) so it stays testable in isolation.

## Decision

Organize by feature, not by technical layer: `feature/<name>/{domain,data,routing,di}`, with only genuinely
cross-cutting infrastructure (auth plumbing, DB access, config, Ktor plugin setup) in `core/`. Within a feature,
`domain/` defines interfaces with no framework dependencies; `data/`/`service/` provide implementations, suffixed `*I`
(e.g. `UserRepositoryI` implements `UserRepository`); `di/` wires the two together.

## Alternatives considered

- **Layer-based (`controllers/`, `services/`, `repositories/` at the root)** — familiar, but touching one feature means
  touching several unrelated top-level directories, and unrelated features tend to become coupled through a shared
  "services" package over time.
- **Single flat module with no enforced boundary** — fastest to start, but nothing stops domain logic from depending on
  Ktor/Exposed types directly, which makes unit-testing business rules without a running server/DB harder as the
  codebase grows.

## Consequences

Adding a feature means creating a new `feature/<name>/` tree, not touching four existing directories. Removing a feature
is close to deleting one directory. The cost is more directories/files for a given amount of logic than a flat layout,
and the convention only holds if `core/` is kept free of business logic — if something there starts making domain
decisions, it belongs in a feature instead.
