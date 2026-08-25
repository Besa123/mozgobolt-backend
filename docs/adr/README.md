# Architecture Decision Records

An ADR captures one significant, hard-to-reverse decision — the alternatives considered and why one was chosen — so the
reasoning survives past the PR that made it. Not every change needs one; a new endpoint doesn't, a new dependency
category or a change to the auth model does.

## When to write one

- Adopting/replacing a major dependency (framework, ORM, auth library, message broker)
- Changing an established pattern documented in [CLAUDE.md](../../CLAUDE.md) or
  [ARCHITECTURE.md](../../ARCHITECTURE.md)
- A security-relevant design choice (token model, password hashing, rate-limit tiers)
- Anything a future contributor could plausibly "simplify away" without realizing why it's there

## How

1. Copy [`0000-template.md`](0000-template.md).
2. Name it `NNNN-short-kebab-title.md`, numbered sequentially.
3. Fill it in as part of the same PR that makes the decision — not retroactively, except when backfilling context for a
   decision that predates this log (as with 0002–0004 below).
4. Add a row to the index table.

## Index

| #                                                          | Title                                                | Status   |
|------------------------------------------------------------|------------------------------------------------------|----------|
| [0001](0001-record-architecture-decisions.md)              | Record architecture decisions                        | Accepted |
| [0002](0002-feature-based-clean-architecture.md)           | Feature-based Clean Architecture layout              | Accepted |
| [0003](0003-results-over-exceptions-for-business-logic.md) | `AppResult<T, E>` over exceptions for business logic | Accepted |
| [0004](0004-refresh-token-family-rotation.md)              | Refresh-token family rotation with theft detection   | Accepted |
