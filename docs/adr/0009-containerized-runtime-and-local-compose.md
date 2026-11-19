# ADR 0009: Containerized runtime and local Compose stack

**Status:** Accepted

## Context

Running the same application distribution in local and CI environments reduces setup drift. The application needs a
JVM runtime, PostgreSQL, and optionally Valkey for cross-instance relay behavior.

## Decision

Build a multi-stage image from the Gradle application distribution, run it on the supported JRE 21 base image as a
non-root user with the JVM timezone set to UTC, and provide Docker Compose for a loopback-only local
app/PostgreSQL/Valkey stack. Compose is a development environment, not a production deployment specification.

## Consequences

The runtime image excludes the JDK, tests, and source checkout, and CI builds and readiness-smoke-tests it. Local
database and image state persist in named volumes until explicitly removed. Production still requires deployment-owned
secret management, networking, ClamAV, scaling, and release orchestration; this ADR does not add a CD system.
