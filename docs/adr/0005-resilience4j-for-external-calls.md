# ADR 0005: Resilience policies for external calls

**Status:** Accepted (retrospectively documented)

## Context

Email, storage, password-breach, push, and virus-scanning integrations can fail or become slow independently of the
backend. Ad-hoc retries at call sites risk retrying permanent failures or multiplying load.

## Decision

External calls use the configured Resilience4j policies through `ResiliencePolicy.execute`. Where both controls apply,
the circuit breaker wraps retry, and retries are limited to transient failures.

## Consequences

Callers share explicit resilience profiles and do not invent local retry loops. A database transaction is never held
across a network call, and remote side effects are not described as exactly-once.
