# 0008. Cloudflare R2 as the pantry entry image storage backend

**Status:** Accepted

**Date:** 2026-09-07

## Context

[ADR 0007](0007-pantry-entry-image-storage-and-sanitization.md) shipped pantry entry images on local disk
(`LocalDiskImageStorage`) behind the `ImageStorage` port, deliberately deferring an S3-compatible backend: this repo had
no cloud infra to plug into yet, and building one speculatively would have been YAGNI. That trade-off is revisited here,
not because local disk was wrong, but because the option was explicitly left open for exactly this: a config-driven swap
with no service or route changes.

Self-hosting an S3-compatible server (SeaweedFS, Garage) was considered first, since it keeps everything local and is a
real skill worth having — but for a single-user app at this scale, running and maintaining a whole extra service
indefinitely is overhead disproportionate to the payoff. Cloudflare R2 was chosen instead: a managed, S3-compatible
object store with a free tier (10GB storage, 1M writes/month, 10M reads/month, **zero egress fees**) that this app's
actual usage (≤3 images × 5MB per pantry entry) will not come close to exceeding.

MinIO — historically the default answer to "S3-compatible, self-hosted" — was ruled out regardless of the
self-host-vs-managed question: it entered maintenance mode in December 2025 and was archived in April 2026.

## Decision

Add `S3ImageStorage` (`core/data/media/S3ImageStorage.kt`), a generic AWS SDK v2 (`software.amazon.awssdk:s3`)
implementation of `ImageStorage` — nothing in it is Cloudflare-specific; it works against R2, real AWS S3, or any other
S3-compatible endpoint via `config.endpoint`. `forcePathStyle(true)` is required because R2 (and most non-AWS
S3-compatible servers) doesn't support AWS's bucket-as-subdomain addressing.

`core/di/DependencyInjection.kt` selects it the same way `EmailService`/`VirusScanner` already select their
implementations — config-driven, defaulting to the existing local-disk fallback:

```kotlin
provide<ImageStorage> {
    if (appConfig.objectStorage.enabled) {
        S3ImageStorage(S3ImageStorage.buildClient(appConfig.objectStorage), appConfig.objectStorage.bucket)
    } else {
        LocalDiskImageStorage(appConfig.media.localStorageDirectory)
    }
}
```

`S3ImageStorage` takes an already-built `S3Client` rather than building one internally from config — a deliberate seam:
every `S3Client` operation is a `default` interface method (verified directly against the SDK jar — every one throws
`UnsupportedOperationException` unless overridden), so a test can hand it a fake implementing only `putObject`/
`getObject`/`deleteObjects` without needing a real network call for pure logic tests. `S3ImageStorageIntegrationTest`
separately verifies the actual wire calls against a real S3-protocol server via
[Adobe's S3Mock](https://github.com/adobe/S3Mock) (Apache 2.0, Testcontainers module, no signup — chosen over
`testcontainers-localstack` specifically because LocalStack started requiring an auth token in March 2026).

**Resilience is asymmetric with email/ClamAV, deliberately.** `withS3Resilience` wraps only a circuit breaker, no retry:
`S3ImageStorage.buildClient` configures the client's own retry behavior directly (`RetryMode.STANDARD` plus
`apiCallTimeout`/`apiCallAttemptTimeout`), tuned to S3's own throttling/error signals in a way a generic Resilience4j
retry can't replicate — stacking a second retry on top would just compound backoff delays. That configuration is
explicit rather than assumed:
checked directly against the SDK jar, the client's *ambient* default retry mode turns out to be gated behind an
`AWS_NEW_RETRIES_2026` system property, falling back to a per-service flag that can resolve to `LEGACY` if unset — not
the fixed constant it would be reasonable to assume. The explicit
`apiCallTimeout`/`apiCallAttemptTimeout` also close a gap this client had no protection against at all: without them, a
wedged R2 endpoint would hang the calling coroutine indefinitely, the same class of bug `ClamAvVirusScanner` needed
`withTimeout`/`runInterruptible` for. The circuit breaker is the genuinely additive piece Resilience4j still brings,
since the SDK has no equivalent for tracking failure health *across* calls to fail fast during a sustained outage.

## Alternatives considered

- **SeaweedFS / Garage (self-hosted, local)** — genuinely local and better as a learning exercise, but real ongoing
  maintenance (another service to run, update, keep an eye on disk for) for a single-user app. Still a config-only swap
  away later: either would use this exact same
  `S3ImageStorage`, just pointed at a local endpoint instead of R2's.
- **MinIO** — ruled out outright: maintenance mode since December 2025, archived April 2026.
- **`testcontainers-localstack` for testing** — rejected once its March 2026 auth-token requirement surfaced; S3Mock
  needs no account of any kind and is Apache 2.0.
- **Keep local disk only** — still entirely valid for a single-user app and remains the zero-config default; R2 is
  opt-in via `OBJECT_STORAGE_ENABLED`, not a replacement.

## Consequences

Local disk stays the zero-config default (`OBJECT_STORAGE_ENABLED` unset/false) — nothing breaks for anyone who hasn't
configured R2. Enabling R2 needs four values from the Cloudflare dashboard (`OBJECT_STORAGE_ENDPOINT`, `_BUCKET`,
`_ACCESS_KEY_ID`, `_SECRET_ACCESS_KEY`; see `.env.example`)
via R2 → Manage API Tokens → create a token with Object Read & Write. Switching backends again later (a different
S3-compatible server, or back to local disk) is a config change only — no code, service, or route changes, which is the
entire point of `ImageStorage` being a port. Regression to watch for:
any new `ImageStorage` implementation that builds its own client internally instead of accepting one, losing the
fake-injection seam `S3ImageStorageTest` depends on.
