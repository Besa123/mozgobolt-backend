# ADR 0002: Feature-based Clean Architecture

**Status:** Accepted (retrospectively documented)

## Context

The backend has multiple business capabilities with different persistence, service, and HTTP concerns. A layer-only
layout would group unrelated features together and make feature ownership harder to see.

## Decision

Organize business code under `feature/<name>/{domain,data,service,routing,di}`. Keep cross-cutting infrastructure in
`core/`. Domain interfaces describe behavior; feature data and service implementations satisfy those interfaces.

## Consequences

Feature boundaries and ownership are visible in the directory tree, and implementation details can change without
moving unrelated features. The trade-off is that shared behavior must be deliberately placed in `core/` rather than
reached across feature implementation packages. `ArchitectureRulesTest` guards key dependency boundaries.
