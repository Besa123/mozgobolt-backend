# 0003. `AppResult<T, E>` over exceptions for business logic

**Status:** Accepted

**Date:** 2026-08-25 (backfilled — reflects `core/domain/AppResult.kt`, in place since early commits)

## Context

Business logic has expected failure modes (invalid credentials, duplicate email, locked account) that callers must
handle. Kotlin exceptions make those paths invisible at the call site — nothing in a signature says it can throw — and
easy to forget; `kotlin.Result` can't model a specific, typed error per operation.

## Decision

Business logic returns `AppResult<T, E>` — sealed `Success<T>` / `Error<E>` — instead of throwing for expected failures.
`E` is a domain-specific sealed error type per operation, not a generic string or exception. Callers use
`.fold(onSuccess, onError)`; the compiler enforces both branches. Exceptions are reserved for genuinely unexpected
failures, caught at the edge by `StatusPages` without leaking internals.

## Alternatives considered

- **Throw typed exceptions** — idiomatic in Java, but in Kotlin the signature communicates nothing and unchecked
  exceptions surface in production as unhandled 500s.
- **`kotlin.Result<T>`** — its error channel is `Throwable`, not an exhaustively-matchable sealed `E` per operation.
- **Nullable returns (`T?`)** — loses the failure reason entirely.

## Consequences

Every business function's signature states its success and failure shapes, and `when`/`.fold()` over a sealed `E` is
exhaustiveness-checked — a new error case can't go silently unhandled. Costs: a sealed error class per operation family,
and the discipline to bridge with `runSuspendCatching` (not `runCatching`) so coroutine cancellation isn't swallowed.
