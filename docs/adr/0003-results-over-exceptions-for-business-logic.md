# 0003. `AppResult<T, E>` over exceptions for business logic

**Status:** Accepted

**Date:** 2026-08-25 (backfilled — reflects `core/domain/AppResult.kt`, in place since early commits)

## Context

Business logic has expected failure modes — invalid credentials, a duplicate email on registration, a locked-out
account — that are not exceptional in the sense that matters for control flow; they're outcomes the caller must handle.
Using Kotlin exceptions for these makes the failure paths invisible at the call site (nothing in a function signature
says it can throw) and easy to forget to handle, and
`kotlin.Result` doesn't let a specific, typed error be modeled per operation.

## Decision

Business logic returns `AppResult<T, E>` — a sealed `Success<T>` / `Error<E>` type — instead of throwing for expected
failures. `E` is a domain-specific error type per operation (e.g. a sealed class of registration failure reasons), not a
generic string or exception. Callers use `.fold(onSuccess, onError)`
and the compiler enforces that both branches are handled. Actual exceptions are reserved for genuinely unexpected
failures (bugs, infrastructure errors) and are caught at the edge by Ktor's `StatusPages`, never leaking internals into
the response.

## Alternatives considered

- **Throw typed exceptions for expected failures** — idiomatic in Java, but in Kotlin nothing in the function signature
  communicates that a call can fail, and unchecked exceptions are easy to miss until they surface in production as an
  unhandled 500.
- **`kotlin.Result<T>`** — close, but its error channel is `Throwable`, which doesn't give each operation a precise,
  exhaustively-matchable error type the way a custom sealed `E` does.
- **Return nullable types (`T?`)** — loses the reason for failure entirely; can't distinguish "not found"
  from "invalid input" from "locked out."

## Consequences

Every business-logic function's signature states its success and failure shapes explicitly, and
`when`/`.fold()` over a sealed error type is exhaustive-checked by the compiler — a new error case can't be silently
unhandled. The cost is some boilerplate (a sealed error class per operation family) and the discipline to use
`runSuspendCatching` — not `runCatching` — when bridging into this model from suspending code that might throw, so
coroutine cancellation isn't swallowed.
