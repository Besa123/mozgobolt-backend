# 0002. Feature-based Clean Architecture layout

**Status:** Accepted

**Date:** 2026-08-25 (backfilled — reflects the layout in place since the project's early commits)

## Context

The codebase needed a structure that scales past a handful of endpoints without features entangling through shared
top-level layers, while keeping domain logic free of framework types (Ktor, Exposed) so it stays testable in isolation.

## Decision

Organize by feature, not by technical layer: `feature/<name>/{domain,data,routing,di}`, with only genuinely
cross-cutting infrastructure (auth plumbing, DB access, config, Ktor plugin setup) in `core/`. Within a feature,
`domain/` defines framework-free interfaces; `data/`/`service/` implement them, suffixed `*I` (`UserRepositoryI`
implements `UserRepository`); `di/` wires the two.

## Alternatives considered

- **Layer-based (`controllers/`, `services/`, `repositories/` at the root)** — touching one feature means touching
  several unrelated top-level directories, and features couple through shared "services" packages over time.
- **Single flat module, no enforced boundary** — fastest to start, but nothing stops domain logic from depending on
  Ktor/Exposed directly, making business rules untestable without a running server/DB.

## Consequences

Adding a feature is a new `feature/<name>/` tree; removing one is close to deleting a directory. The cost is more files
per unit of logic, and the convention only holds if `core/` stays free of business logic — anything there making domain
decisions belongs in a feature.
