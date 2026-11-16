# Security probe suite

An opt-in, black-box probe suite against a real local Ktor/Netty process, written in Kotlin
(`src/test/kotlin/com/mozgobolt/securityprobe/`) and run with `./gradlew runSecurityProbes`. It checks the running app's
public HTTP surface against the OWASP API Security Top 10 (2023) and the non-negotiable invariants CLAUDE.md already
commits to — not against whatever a given handler currently happens to do. Every probe's expected outcome is written
down, as that probe's own `expectation` string, before it makes a single request; nothing here was derived by reading
a handler's implementation and restating it as the check.

Each run registers its own disposable throwaway accounts via the public `/auth/register` + `/auth/login` endpoints —
it needs no seeded fixtures and leaves no state anyone depends on afterward.

## What it checks

- **Broken Function Level Authorization (API5):** a buyer token cannot submit vendor telemetry; a vendor token cannot
  open the buyer live map, ping a vehicle, or create a company; a company member who isn't its admin cannot read the
  invite code.
- **Broken Object Level Authorization (API1):** a vendor cannot patch, archive, or list the members of another
  company's vehicle/roster; a buyer cannot delete or read another buyer's saved location or device installation —
  cross-tenant attempts come back `404`, never a silent success, matching the handlers' own deliberate "not-found-shaped,
  not forbidden-shaped" design (no confirmation that a given id belongs to someone else).
- **Broken Authentication (API2):** a protected endpoint rejects a missing/malformed `Authorization` header, a token
  with a tampered signature, an `alg: none` forgery, and a token re-signed with a commonly guessed secret; refresh-token
  reuse is rejected and revokes the *whole* token family (CLAUDE.md's own stated invariant), not just the reused
  token; a live-map SSE stream actually stops delivering within its ~30s session-liveness recheck window once
  `/auth/logout-all` revokes the session.
- **Broken Object Property Level Authorization (API3):** registering with `role: "ADMIN"` (not a real `UserRole`) is
  rejected, not silently ignored into a default role or accepted as a privileged account.
- **Unrestricted Resource Consumption (API4):** the documented `AUTH_LIMIT` (5/min/IP) and `API_LIMIT` (60/min/user)
  are enforced end to end; a field over its documented max length, a telemetry batch over its max point count, and a
  raw body over its endpoint's `BodyLimit` are all rejected, not silently truncated or accepted.
- **Injection / Security Misconfiguration (API8):** classic SQL/script-metacharacter and NUL-byte payloads in a
  free-text field never produce a 500 or a leaked stack trace/database error; a malformed non-JSON body is a clean
  400; the hardened response headers (`core/modules/plugin/DefaultHeadersConfig.kt`) are present on success *and*
  error responses; CORS never reflects a disallowed origin back with credentials enabled; `scope=all` on the live map
  is rejected exactly as its parser documents, never a silent nationwide feed.
- **Automated-client patterns, not just one-shot checks** (`BotPatternProbes.kt`): a credential-stuffing run — wrong
  passwords against one account from a distinct source address each time, so no single address ever approaches the
  per-IP `AUTH_LIMIT` — is stopped by `LockoutPolicy`'s per-*account* lockout once the bot finally finds the right
  password; a sweep of the paths mass internet scanners probe on every host within minutes of it going live (`.env`,
  `.git/config`, `wp-admin`, `actuator/env`, a default admin panel, ...) finds nothing exposed; replaying a captured
  `Idempotency-Key` with a tampered request body is rejected (`422`), not silently applied or served from a stale
  cached response.

## What it is not

It is not a fuzzer and does not try to discover unknown vulnerability classes; it is a checklist of specific,
pre-stated contracts, run against the real app so that a future change which silently breaks one of them is caught.
A probe that fails is a finding to investigate against the real app and database, the same way the two bugs below
were found and fixed — not a report to be explained away by weakening the probe.

## What it found (and the app fixes that followed)

- A revoked buyer session (`POST /auth/logout-all`) kept receiving live vehicle locations indefinitely: every
  `GET .../live` route scheduled its reconnect-warning timer with a bare `launch { }`, left uncancelled when the
  collection loop ended early, keeping the handler's coroutine scope — and the connection — open. Fixed via
  `runSseCollectionWithReconnectWarning` (`core/modules/plugin/SseConventions.kt`), applied to all three live routes.
  Verified against the real app with `curl`: the connection now closes within one ~30s recheck cycle of revocation.
- A saved-location label containing a NUL byte reached Postgres and surfaced as an uncaught HTTP 500 instead of a
  clean 400: `validateDisplayName`'s character blocklist covered markup characters but not control characters. Fixed
  by extending it to reject the Unicode `Cc` (control) category.

## Run it

```bash
./gradlew runSecurityProbes
./gradlew runSecurityProbes --args="http://127.0.0.1:18080 build/security-probe/my-report.json"
```

It refuses any non-loopback base URL for the same reason the load test does: it registers disposable accounts and
fires rate-limit/auth bursts that are only meaningful against a local disposable instance. The process exits non-zero
if any probe fails or errors; the full report (every probe's category, name, expectation, and verdict) is written to
`build/security-probe/security-probe-report.json` and printed to stdout.

## What it does not cover

Company invite-code brute-forcing/enumeration, SSRF (the app has no user-supplied-URL fetch path today), file-upload
content checks beyond what `VehicleRoutes`' ClamAV/re-encoding path already covers, and the shared-infrastructure
question of whether a *production* reverse proxy correctly overwrites `X-Forwarded-For` before the app ever sees it —
this suite's own synthetic addresses are trusted exactly as documented for this bare local instance
(`configureForwardedHeaders` trusts one hop with no cryptographic verification), which is a known, accepted trust
boundary for local use, not something black-box HTTP probes against a bare instance can validate either way. Add
coverage here as new trust boundaries or invariants are introduced.
