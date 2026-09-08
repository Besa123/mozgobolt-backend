# 0007. Pantry entry image storage and sanitization

**Status:** Accepted

**Date:** 2026-09-07

## Context

Users can attach up to 3 pictures to a pantry entry (add/replace/remove). There is no existing precedent in this
codebase: no image handling, no object storage, no upload route, no `docker-compose` provisioning any storage backend.
Three decisions had to be made together: where the bytes live, how an upload is defended against a malicious or
malformed file, and how those defenses degrade when a scanner isn't available.

## Decision

### Storage: local disk behind a port, not S3

> **Update (see [ADR 0008](0008-r2-object-storage-for-pantry-entry-images.md)):** the S3-compatible backend deferred
> below has since been added (`S3ImageStorage`, config-selected, defaulting to Cloudflare R2) — the trade-off this
> section describes is exactly what got revisited. Local disk remains the zero-config default; everything else on
> this page (sanitization, ClamAV) is unaffected and still current.

`ImageStorage` (`core/domain/media/ImageStorage.kt`) is a small port — `store`/`read`/`deleteBestEffort` — implemented
today by `LocalDiskImageStorage`, which writes under a configured directory using server-generated UUID keys (never
client input) and a temp-file-then-atomic-rename so a concurrent reader never observes a partial write. Nothing in the
domain or service layer knows the backend is local disk.

This was chosen over an S3-compatible object store with presigned upload/download URLs — the stronger option for
scalability, since large binaries would never touch the app server at all — because that requires provisioning a bucket
and credentials before the feature even runs locally, and this repo has no cloud infra to plug into yet. The port exists
specifically so that trade-off can be revisited later (a new `ImageStorage` implementation) without touching
`PantryEntryImageServiceI` or any route.

### Sanitization: mandatory re-encode is the primary defense

Every upload is decoded fully into pixels and re-encoded fresh as JPEG (`JavaImageSanitizer`), regardless of source
format. This is deliberate and non-optional: the output bytes are 100% freshly generated from decoded pixel data, so no
EXIF/metadata, no trailing polyglot payload, and no embedded script from the original file can survive the round-trip.
This is a stronger and simpler guarantee than trying to enumerate and block individual attack shapes (hidden scripts,
disguised executables, malicious metadata blocks) — none of them can survive a full decode/re-encode cycle regardless of
what they were.

Before any full decode, the declared width/height is probed via `ImageIO`'s `ImageReader` (a header read, not a full
decode) and rejected if the pixel count exceeds a configured budget — a decompression-bomb guard, since a tiny file can
otherwise be crafted to force an enormous in-memory bitmap allocation. Only JPEG/PNG are accepted, sniffed from the
actual bytes via `ImageIO`'s registered readers, never the client's `Content-Type` header or filename.

This isn't a bespoke technique: it's the approach the
[OWASP File Upload Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html) itself
recommends for images ("image rewriting... destroys any kind of malicious content injected in an image"), citing
[righettod/document-upload-protection](https://github.com/righettod/document-upload-protection) as a reference
implementation that uses this exact `ImageIO.createImageInputStream()` / `getImageReaders()` / re-encode pattern.

### ClamAV: optional defense-in-depth, off by default, fails closed when enabled

`VirusScanner` (`core/domain/security/VirusScanner.kt`) is a second, independent layer ahead of sanitization — raw bytes
are scanned before anything is decoded. `ClamAvVirusScanner` talks to a local (or remote) `clamd` daemon via
[`xyz.capybara:clamav-client`](https://github.com/cdarras/clamav-client) — a small Kotlin client for clamd's
`INSTREAM` protocol; `NoOpVirusScanner` is used when `clamAv.enabled` is false (the default), mirroring exactly how
`EmailService` selection already falls back to `LoggingEmailService` when `RESEND_API_KEY` is unset
(`core/di/DependencyInjection.kt`).

A library was chosen over hand-rolling the `INSTREAM` protocol once one was confirmed worth trusting: the more prominent
alternatives (`fi.solita:clamav-client`, `hu.alphabox:clamav-client`) haven't shipped a release since 2016/2018
respectively, but `xyz.capybara:clamav-client` has ongoing commit/issue activity, is MIT-licensed, and is itself written
in Kotlin — a better fit for this codebase than any of the Java wrappers. Per CLAUDE.md's "reuse before you reinvent," a
real dependency beats a bespoke socket client once it clears that bar.

Unlike email, there's no "refuse to boot in production" clause: sending a real verification email is essential to the
app's security model with no substitute, whereas the mandatory re-encode above is already a real, independent defense
against malicious content — running without ClamAV is a legitimate default, not a silently accepted gap. When ClamAV
*is* enabled, an unreachable daemon fails the upload closed (`SCAN_UNAVAILABLE`) rather than silently skipping the
scan — the operator explicitly opted into requiring it.

The library opens a plain blocking `java.nio.channels.SocketChannel` with no read/connect timeout of its own.
`ClamAvVirusScanner` bounds the call with `withTimeout(config.timeoutMs)`, but a bare `withContext(Dispatchers.IO)`
around a blocking call like this wouldn't actually free the underlying thread when that fires — cancellation doesn't
forcibly interrupt a plain blocking call. `runInterruptible` is what closes that gap: `SocketChannel` is an
`InterruptibleChannel`, so a thread blocked reading from one aborts the moment it's interrupted, and
`runInterruptible` interrupts its thread exactly when the coroutine running it is cancelled — which is exactly what
`withTimeout` firing does. Verified with a test that points the scanner at a socket that accepts the connection and then
never replies: the scan still returns `Unavailable` within the configured timeout, not after the OS-level TCP timeout
(which can be minutes).

### Known residual race: the per-entry image cap

`addImage` checks `countByEntryId(entryId) < maxImagesPerEntry` once before the slow scan/sanitize step and again
immediately before the insert (inside the same transaction as the insert) — the second check narrows, but cannot by
itself eliminate, the window where two concurrent `addImage` calls for the *same* entry (e.g. from two devices, which
this app's multi-device sync explicitly anticipates) both pass a check before either commits, landing one image over the
configured cap. Closing this fully needs a row lock on the parent `pantry_entries` row (e.g. Exposed's
`forUpdate()`) to serialize concurrent image mutations per entry — deferred rather than attempted without a real
database in the loop to verify the locking behavior against; revisit if this cap is ever relied on as a hard guarantee
rather than a soft limit.

### Limits

3 images per pantry entry, 5MB per upload (`BodyLimit.LARGE`, already defined and commented "image uploads" before this
feature existed). `Content-Length` alone isn't trusted as the size limit — it can be absent or wrong under chunked
transfer encoding — so `limitedFileUploadPost`/`Put` (`core/modules/plugin/RouteBuilders.kt`) also enforces the cap as a
hard ceiling on bytes actually read while streaming the multipart body.

## Alternatives considered

- **S3/R2/MinIO with presigned URLs now** — better scalability posture, but blocked on infra this repo doesn't have;
  deferred behind the `ImageStorage` port instead of built speculatively (YAGNI).
- **Skip re-encoding, only scan** — a scanner catches known signatures but not novel polyglot/metadata tricks, and
  running without ClamAV configured (the default) would then mean no defense at all. Re-encoding is a structural
  guarantee, not signature-dependent, so it's the one that must always run.
- **ClamAV required, refuse to boot in production without it** — considered and rejected: unlike a leaked password reset
  token, there's a strong independent primary defense already in place, so mandating a scanner across every deployment
  isn't proportionate to the residual risk.

## Consequences

A future move to S3-compatible storage is a new `ImageStorage` implementation plus a config/DI change — no service or
route changes. Enabling ClamAV requires a reachable `clamd` (`CLAMAV_ENABLED=true`, `CLAMAV_HOST`, `CLAMAV_PORT`) —
without one, leaving it enabled fails every upload, by design. Regressions to watch for: any code path that stores or
serves the client's original upload bytes instead of `ImageSanitizer`'s output, or a new upload route that bypasses
`limitedFileUploadPost`/`Put`'s streaming size cap.

Two mistakes made and caught during this feature's own review, worth keeping in mind for the next one:

- **A fully-implemented, fully-unit-tested route that's never wired into `configureRouting()` looks identical to a
  working one** from inside its own isolated route tests — every such test installs its own hand-assembled subset of the
  app via `install<Feature>RoutesTestApp()`, never the real `configureRouting()`. `RoutingWiringTest.kt` now calls the
  real function and asserts every feature's top-level path resolves to something other than 404; add a path to it when a
  new feature's routes are wired in.
- **A service that reads/writes through Exposed must wrap every such call in `tx.transactional { }`**, not just the
  final write — Exposed throws outside an active transaction, and none of this app's repository implementations open one
  themselves. This is invisible to unit tests running against fakes/`NoopTransactionalRunner` and only surfaces against
  a real database, which is exactly why it shipped unnoticed here; the Docker-gated integration tests are the only ones
  that would have caught it; keep them, and run them locally with Docker at least once before trusting a new
  persistence-touching method.
