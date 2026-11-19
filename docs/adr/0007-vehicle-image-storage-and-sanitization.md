# ADR 0007: Vehicle image validation and storage

**Status:** Accepted (retrospectively documented)

## Context

Uploaded image files are untrusted input. File extensions and claimed content types do not prove that the bytes are a
safe, decodable image.

## Decision

Bound upload size, decode images, and re-encode from pixel data before storage. Production requires ClamAV scanning and
fails closed when the scanner is unavailable. Storage can be local or S3-compatible, configured independently of the
sanitization and scanning controls.

## Consequences

Sanitization and malware scanning are complementary and neither is treated as a substitute for the other. The
production configuration rejects startup when scanning is disabled; upload tests cover malformed images and scanner
failure behavior.
