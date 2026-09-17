# 0008. Cloudflare R2 as the pantry entry image storage backend

**Status:** Accepted

**Date:** 2026-09-07

## Context

[ADR 0007](0007-pantry-entry-image-storage-and-sanitization.md) shipped images on local disk behind the
`ImageStorage` port, deliberately deferring an S3-compatible backend (no cloud infra to plug into yet). That trade-off
is revisited here — the port existed for exactly this, a config-only swap.

Self-hosting (SeaweedFS/Garage) was considered first but is ongoing operational overhead disproportionate to a
single-user app. Cloudflare R2 was chosen instead: managed, S3-compatible, free tier (10GB storage, 1M writes/mo, 10M
reads/mo, zero egress) well above this app's actual usage (≤3 images × 5MB/entry). MinIO was ruled out regardless of the
self-host-vs-managed question: maintenance mode since December 2025, archived April 2026.

## Decision

`S3ImageStorage` (`core/data/media/S3ImageStorage.kt`) — a generic AWS SDK v2 implementation of `ImageStorage`, nothing
Cloudflare-specific; works against R2, real S3, or any S3-compatible endpoint via `config.endpoint`.
`forcePathStyle(true)` is required — R2 (like most non-AWS S3-compatible servers) doesn't support bucket-as-subdomain
addressing.

`DependencyInjection.kt` selects it config-driven, the same pattern `EmailService`/`VirusScanner` already use,
defaulting to `LocalDiskImageStorage`.

`S3ImageStorage` takes an already-built `S3Client` rather than constructing one internally — every `S3Client`
method is a `default` interface method that throws `UnsupportedOperationException` unless overridden, so a test can hand
it a fake implementing only the methods it needs, no real network call required. Wire-level behavior is covered
separately by `S3ImageStorageIntegrationTest` against
[Adobe's S3Mock](https://github.com/adobe/S3Mock) (chosen over `testcontainers-localstack` once LocalStack started
requiring an auth token in March 2026).

**Resilience is asymmetric with email/ClamAV, deliberately**: `withS3Resilience` wraps only a circuit breaker, no
retry — `buildClient` configures the SDK's own retry (`RetryMode.STANDARD` + explicit `apiCallTimeout`/
`apiCallAttemptTimeout`, since the SDK's *ambient* default retry mode is gated behind a system property and can silently
resolve to `LEGACY` if unset). Stacking a second retry on top would just compound backoff delays; the circuit breaker is
the piece Resilience4j still adds, since the SDK has no cross-call failure-health tracking.

## Alternatives considered

- **SeaweedFS/Garage** — real ongoing maintenance for a single-user app; still a config-only swap away later via this
  same `S3ImageStorage`.
- **MinIO** — ruled out: maintenance mode since December 2025, archived April 2026.
- **`testcontainers-localstack`** — rejected once its March 2026 auth-token requirement surfaced.
- **Keep local disk only** — still valid and remains the zero-config default; R2 is opt-in via
  `OBJECT_STORAGE_ENABLED`.

## Consequences

Local disk stays the zero-config default. Enabling R2 needs four Cloudflare dashboard values (see
`.env.example`). Switching backends again later is a config change only — no code/service/route changes. Regression to
watch for: any new `ImageStorage` implementation that builds its own client internally instead of accepting one,
breaking the fake-injection seam `S3ImageStorageTest` depends on.
