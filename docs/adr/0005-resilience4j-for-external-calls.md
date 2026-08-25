# 0005. Resilience4j for external API calls

**Status:** Accepted

**Date:** 2026-08-25

## Context

The app has one outbound integration today (the Resend email API for verification emails), with more likely later
(payments, SMS, other third-party APIs). A direct, unprotected call to any external service has two failure modes that
matter in production: transient failures (a network blip, a brief timeout) that would succeed if retried, and sustained
degradation (the dependency is actually down or overloaded) where retrying — or even calling at all — just adds load to
a system that's already struggling and makes the caller's own request pile up waiting on it.

Hand-rolling retry loops and failure tracking per call site is exactly the kind of thing that's easy to get subtly wrong
(no backoff, no jitter, no shared failure-rate tracking across calls) and inconsistent across future external
dependencies if each one reinvents its own version.

## Decision

Use [Resilience4j](https://resilience4j.readme.io/) (with its `resilience4j-kotlin` coroutine extensions)
for retry and circuit-breaker behavior around every external API call. A single `ResilienceRegistry`
(`core/modules/plugin/ResilienceConfig.kt`) holds one `Retry` + `CircuitBreaker` pair per external dependency, and each
dependency gets a small `suspend fun withXResilience(block: suspend () -> T): T`
wrapper (e.g. `withEmailResilience`) as its call-site API. `ResilientEmailService` (`core/data/email/`)
decorates the existing `EmailService` interface with this behavior, so the domain layer and DI wiring stay unaware
resilience is happening — swapping it in was a decorator change, not a domain change.

For email specifically: retry up to 3 times with exponential backoff (100ms, 200ms, 400ms); the circuit-breaker opens
once 50%+ of the last 20 calls fail or exceed a 5s slow-call threshold, staying open for 30s before allowing a test call
through. Two details here are easy to get backwards. First, the circuit-breaker wraps the retry, not the reverse — it
takes one permission check and records one success/failure per logical call, evaluated after all retry attempts have
finished, so its failure-rate tracking reflects real outcomes rather than retry noise, and an open circuit skips the
whole retry sequence in one shot instead of each attempt hitting it independently. Second, retry only fires on failures
`ResendEmailService` classifies as transient (network error, 429, or Resend-side 5xx) via
`.retryExceptions(TransientEmailDeliveryException::class.java)` — the Resend SDK exposes no structured exception
hierarchy, so this classification is a deliberate step, not a default; retrying a
`PermanentEmailDeliveryException` (bad API key, rejected recipient) would fail identically on every attempt. The
circuit-breaker still counts both exception types as failures — only the retry decision distinguishes them.

## Alternatives considered

- **Hand-rolled retry loop per call site** — no external dependency, but no shared circuit-breaker state (each call site
  would independently decide "is this service healthy?"), inconsistent backoff strategy across the codebase, and it's
  the kind of infrastructure code that's easy to get wrong under time pressure (missing jitter, off-by-one attempt
  counts, blocking sleeps instead of suspending delays).
- **No resilience at all, let failures propagate** — simplest, but a single transient network blip fails a user's
  registration/verification-email flow with no recovery, and a Resend outage would have every concurrent registration
  hang or fail slowly instead of failing fast.
- **A different library (e.g. Failsafe, custom coroutine-based backoff)** — Resilience4j is the de facto standard for
  JVM resilience patterns, has first-class Kotlin coroutine support (`resilience4j-kotlin`), and is the library most JVM
  backend engineers will already recognize — lower learning cost than a custom implementation or a less-common library.

## Consequences

Adding resilience to a new external dependency means adding one `Retry`/`CircuitBreaker` pair to
`ResilienceRegistry`, one wrapper function, and a transient/permanent exception classification for that dependency's
failures — not writing new retry logic. The circuit-breaker's thresholds (50% failure rate, 20-call sliding window, 30s
open duration) are tuned for a single low-volume dependency and may need revisiting once there's real production traffic
data. A future contributor (or AI assistant) reaching for a raw `try/catch` instead of `withXResilience`, wrapping retry
around the circuit-breaker instead of the reverse, or leaving `.retryExceptions()` empty (which silently retries
everything) is regressing this decision — see [CLAUDE.md](../../CLAUDE.md) and
[ARCHITECTURE.md](../../ARCHITECTURE.md#resilience-for-external-calls).
