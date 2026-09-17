# 0007. Pantry entry image storage and sanitization

**Status:** Accepted

**Date:** 2026-09-07

## Context

Pantry entries can have up to 3 images. No prior precedent in this codebase (no image handling, no object storage, no
upload route). Three decisions needed together: where the bytes live, how an upload is defended against a
malicious/malformed file, and how that defense degrades without a scanner.

## Decision

### Storage: port + local disk default (extended, not replaced, by ADR 0008)

`ImageStorage` (`core/domain/media/ImageStorage.kt`) is a small port (`store`/`read`/`deleteBestEffort`).
`LocalDiskImageStorage` writes under a configured directory using server-generated UUID keys (never client input),
temp-file-then-atomic-rename so a reader never observes a partial write. An S3-compatible backend (`S3ImageStorage`) has
since been added — see [ADR 0008](0008-r2-object-storage-for-pantry-entry-images.md); the port made that a config
change, not a service/route change.

### Sanitization: mandatory re-encode is the primary defense

Every upload is fully decoded to pixels and re-encoded fresh as JPEG (`JavaImageSanitizer`), unconditionally. No
EXIF/metadata, trailing polyglot payload, or embedded script can survive that round-trip — a structural guarantee, not a
pattern-matching one, per the
[OWASP File Upload Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html)'s own
recommendation for images. Declared width/height is probed via `ImageIO`'s header read (not a full decode)
and rejected over a configured pixel-count budget — a decompression-bomb guard. Only JPEG/PNG, sniffed from actual bytes
via `ImageIO`, never `Content-Type`/filename.

### ClamAV: optional, off by default, fails closed when enabled

`VirusScanner` (`core/domain/security/VirusScanner.kt`) scans raw bytes before decoding, via
[`xyz.capybara:clamav-client`](https://github.com/cdarras/clamav-client) (actively maintained, unlike the older
alternatives) against a `clamd` daemon. `NoOpVirusScanner` is used when `clamAv.enabled` is false (default) —
legitimate, since re-encoding above is already a real, independent defense; unlike email verification, nothing here
makes ClamAV mandatory. When enabled, an unreachable daemon fails the upload closed (`SCAN_UNAVAILABLE`).

**`ClamAvVirusScanner` must use `runInterruptible`, not a bare `withContext(Dispatchers.IO)`**, around the blocking
`SocketChannel` read — the client has no read timeout of its own, and a plain blocking call inside
`withContext` isn't actually freed by coroutine cancellation. `SocketChannel` being an `InterruptibleChannel` is what
makes `runInterruptible` + `withTimeout` actually abort a hung scan instead of leaking a blocked thread. Verified by a
test pointing the scanner at a socket that accepts the connection and never replies.

### Known residual race: per-entry image cap

`addImage` checks the image count once before the slow scan/sanitize step and again just before the insert (same
transaction) — the second check narrows but doesn't eliminate the window where two concurrent uploads to the *same*
entry (two devices) both pass before either commits, landing one image over the cap. Closing this fully needs a row lock
(`forUpdate()`) on the parent entry — deferred, not attempted without a real database to verify the locking behavior
against. Revisit if this cap is ever relied on as a hard guarantee rather than a soft limit.

### Limits

3 images/entry, 5MB/upload (`BodyLimit.LARGE`). `Content-Length` isn't trusted as the real limit (absent/wrong under
chunked encoding) — `limitedFileUploadPost`/`Put` enforces the cap as a hard ceiling on bytes actually read while
streaming the multipart body.

## Alternatives considered

- **S3/R2/MinIO now** — deferred behind the `ImageStorage` port instead of built speculatively (YAGNI); see ADR 0008 for
  when this got revisited.
- **Skip re-encoding, only scan** — a scanner misses novel polyglot/metadata tricks, and running without ClamAV (the
  default) would then mean no defense at all.
- **ClamAV mandatory in production** — rejected; the re-encode defense is already independent and sufficient.

## Consequences

Regression to watch for: any code path serving the client's original upload bytes instead of the sanitizer's output, or
a new upload route bypassing `limitedFileUploadPost`/`Put`'s streaming size cap.

Two mistakes caught during this feature's own review, worth remembering for the next one:

- **A route fully implemented and unit-tested but never wired into `configureRouting()` looks identical to a working
  one** from inside its own isolated route test — each installs its own hand-assembled app, never the real wiring.
  `RoutingWiringTest.kt` calls the real `configureRouting()` and asserts every feature's top-level path resolves to
  non-404 — add a path to it for every new feature.
- **Every Exposed-backed service call must run inside `tx.transactional { }`** — invisible against fakes/
  `NoopTransactionalRunner` in unit tests, only surfaces against a real database. Run the Docker-gated integration tests
  locally at least once before trusting a new persistence-touching method.
