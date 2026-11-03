# Contributing to MozgoBolt

The rules the codebase enforces in CI, written down. For the "why" behind the structure, see
[ARCHITECTURE.md](ARCHITECTURE.md); for rationale behind specific decisions, see [docs/adr/](docs/adr/).

## Prerequisites

- JDK 21
- A local PostgreSQL instance (or point `DB_URL` at any reachable Postgres)
- `.env` file — copy `.env.example` to `.env` and fill in real values. Loaded automatically at startup via
  `dotenv-kotlin`, but only for variables not already set in the environment (CI/prod env vars always win).

## Local setup

```bash
cp .env.example .env    # fill in DB credentials, JWT secret, etc.
./gradlew test          # run the test suite (Testcontainers tests skip without Docker)
./gradlew run           # start the server
```

Flyway migrations run automatically on startup — no manual migration step needed.

## Before opening a PR

Run the same checks CI runs, in this order, and make sure all of them pass:

```bash
./gradlew ktlintCheck         # formatting — auto-fixable with ./gradlew ktlintFormat
./gradlew detekt              # static analysis, zero-tolerance (config/detekt.yml)
./gradlew test koverVerify koverXmlReport koverHtmlReport
```

The Kover tasks run the JVM test suite, enforce the configured line and branch coverage bounds, and write reports to
`build/reports/kover/` (HTML: `build/reports/kover/html/index.html`; XML: `build/reports/kover/report.xml`). CI
(`.github/workflows/ci.yml`) gates on the same coverage verification as well as ktlint, detekt, and tests.
The current minimums are 92% line coverage and 62% branch coverage. PostgreSQL, Redis, and S3 Testcontainers tests
are skipped when Docker is unavailable; because those tests cover persistence and infrastructure code, Docker is
required for `koverVerify` to meet the 92% line and 62% branch minimums. On Colima, Testcontainers may need
`DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

## Dependency updates

The Gradle Versions Plugin checks declared dependencies, plugins, and the Gradle wrapper against stable releases:

```bash
./gradlew dependencyUpdates
```

The report is written to `build/dependencyUpdates/report.txt`. It is informational and does not edit the version
catalog or wrapper; review release notes and run the test suite before changing versions. Use
`./gradlew dependencyUpdates --refresh-dependencies` when checking for a very recent release not yet in Gradle's cache.
The report may also list transitive dependencies supplied by plugins (currently KtLint); do not force those versions
unless the owning plugin documents compatibility and the affected tooling passes its checks.

## CodeQL

`.github/workflows/codeql.yml` runs GitHub CodeQL on pull requests, pushes to `master`, and weekly. It analyzes
production Kotlin with the `security-extended` suite using a manual Gradle build; it does not run tests or require
database credentials.

CodeQL is not a Gradle plugin or a Docker container. To run it locally, install the [CodeQL CLI](https://github.com/github/codeql-cli-binaries/releases)
and put `codeql` on your `PATH`. From the repository root, use a fresh database directory for each run:

```bash
./gradlew clean --no-configuration-cache --console=plain
DB_DIR="build/codeql-local-$(date +%Y%m%d%H%M%S)"
codeql database create "$DB_DIR" \
  --language=java-kotlin \
  --source-root=. \
  --codescanning-config=.github/codeql/codeql-config.yml \
  --command='./gradlew classes -PmigrationPreviewScriptName=codeql-analysis --no-configuration-cache --console=plain'
mkdir -p build/reports/codeql
codeql database analyze "$DB_DIR" \
  --format=sarif-latest \
  --output=build/reports/codeql/results.sarif
```

The local run writes SARIF but does not publish GitHub code-scanning alerts.

## Code conventions & security rules

[CLAUDE.md](CLAUDE.md) is the canonical rule set for both AI-assisted and human contributions — read it before writing
code. [ARCHITECTURE.md](ARCHITECTURE.md) explains the reasoning behind those rules.

## Commit & PR style

- Keep commits scoped to one logical change; write the message in the imperative ("Add X", not "Added X").
- Significant, hard-to-reverse choices get an ADR in the same PR — see [docs/adr/README.md](docs/adr/README.md) for when
  and how.
- **API changes must update the OpenAPI spec** (`src/main/resources/openapi/documentation.json`) — it's the contract
  Swagger UI serves.
