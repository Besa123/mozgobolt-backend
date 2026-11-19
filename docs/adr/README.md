# Architecture Decision Records

An ADR records a choice that affects multiple features, operational boundaries, security, or a costly-to-reverse
design. Use the next available four-digit number and a short kebab-case title.

Each record should include its status, context, decision, and consequences. Link the implementation and regression
tests where possible. Do not silently rewrite an accepted decision: add a new ADR that supersedes it and link both
records.

ADRs 0002–0007 below were documented retrospectively from the current source and repository instructions because the
referenced records were not present in this checkout. They describe the behavior the code currently enforces; they do
not claim to reproduce the original decision history.

## Decisions

- [0002 — Feature-based Clean Architecture](0002-feature-based-clean-architecture.md)
- [0003 — Results for expected business failures](0003-results-over-exceptions-for-business-logic.md)
- [0004 — Refresh-token family rotation](0004-refresh-token-family-rotation.md)
- [0005 — Resilience policies for external calls](0005-resilience4j-for-external-calls.md)
- [0006 — Durable sync outbox and cursor pull](0006-multi-device-sync-outbox-and-cursor-pull.md)
- [0007 — Vehicle image validation and storage](0007-vehicle-image-storage-and-sanitization.md)
- [0008 — Opt-in Prometheus metrics](0008-opt-in-prometheus-metrics.md)
- [0009 — Container image and local Compose stack](0009-containerized-runtime-and-local-compose.md)
- [0010 — UTC policy for timezone-less timestamp storage](0010-utc-policy-for-timestamp-storage.md)
