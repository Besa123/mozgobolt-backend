# 0005. Resilience4j for external API calls

**Status:** Accepted

**Date:** 2026-08-25

## Context

Outbound calls (currently only the Resend email API) fail two ways: transiently, where a retry succeeds, and through
sustained outage, where retrying adds load and ties up the caller. Hand-rolled retry loops per call site are easy to get
wrong (no backoff/jitter, no shared failure-rate state) and drift apart as dependencies are added.

## Decision

Wrap every external API call in [Resilience4j](https://resilience4j.readme.io/) retry + circuit-breaker using the
`resilience4j-kotlin` coroutine extensions. `ResilienceRegistry` (`core/modules/plugin/ResilienceConfig.kt`) holds one
`Retry` + `CircuitBreaker` pair per dependency, exposed as a `suspend fun withXResilience(block)` wrapper
(`withEmailResilience`). `ResilientEmailService` decorates `EmailService`, so the domain layer and DI wiring stay
unaware of resilience.

Email config: 3 attempts total, exponential backoff (100ms, then 200ms); the circuit opens at ≥50% failures or ≥50% slow
calls (>5s) over a 20-call sliding window (evaluated after a minimum of 10 calls), stays open 30s, then half-opens to
probe.

Two non-obvious constraints:

- **The circuit-breaker wraps the retry, not the reverse.** One permission check and one recorded outcome per logical
  call — failure-rate tracking reflects final outcomes rather than retry noise, and an open circuit skips the whole
  retry sequence.
- **Retry fires only on `TransientEmailDeliveryException`** (network error, 429, Resend 5xx), via
  `.retryExceptions(...)`. The Resend SDK exposes no structured exception hierarchy, so `ResendEmailService`
  classifies failures itself; retrying a `PermanentEmailDeliveryException` (bad API key, rejected recipient) would fail
  identically every attempt. The circuit-breaker counts both types as failures.

## Alternatives considered

- **Hand-rolled retry per call site** — no shared circuit state, inconsistent backoff, easy to get wrong.
- **No resilience** — a network blip fails a registration/verification flow; a Resend outage makes every concurrent
  registration hang or fail slowly instead of failing fast.
- **Other libraries (Failsafe, custom coroutine backoff)** — Resilience4j is the JVM standard with first-class coroutine
  support; lowest learning cost.

## Consequences

A new external dependency needs a registry pair, a wrapper function, and a transient/permanent exception
classification — no new retry logic. Thresholds are tuned for one low-volume dependency; revisit with production
traffic. Regressions to watch for: raw `try/catch` instead of `withXResilience`, retry wrapping the circuit-breaker, or
omitting `.retryExceptions()` (which silently retries everything).
