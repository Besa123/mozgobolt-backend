# ADR 0003: Results for expected business failures

**Status:** Accepted (retrospectively documented)

## Context

Expected outcomes such as invalid credentials, missing resources, or insufficient permissions are part of normal
application behavior. Using exceptions for these outcomes obscures the service contract and makes it easier to confuse
business rejection with infrastructure failure.

## Decision

Domain services return `AppResult<T, E>` for expected failures. Routes map those errors to the public API vocabulary.
Exceptions remain for unexpected failures and are handled at the application boundary.

## Consequences

Callers must handle expected failures explicitly, and exception capture remains reserved for exceptional boundaries.
This keeps error mapping visible without exposing database or stack-trace details to API clients.
