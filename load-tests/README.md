# Live workload test

An opt-in, end-to-end workload against a real local Ktor/Netty process and an isolated PostgreSQL database. It is
written in Kotlin (coroutines + `Flow`) and lives in the test source set (`src/test/kotlin/com/mozgobolt/loadtest`), so
it shares the project's ktlint/detekt gates, the wire DTOs, and the Kotlin seeder. It drives only public HTTP: login,
vendor telemetry, and the buyer nearby SSE stream.

## What it models

All numbers come from `workload.json`, which the seeder and the runner both read.

- **100 trucks**, each a vendor with a consented active vehicle assignment, driving a closed 9 km loop at 15–45 km/h
  through many H3 cells and in and out of viewers' radii. Each truck uploads on its own fixed-rate schedule (default one
  point every 5 s), staggered across the interval, with one upload in flight at a time like a real device.
- **500 buyers** with a skewed geography. Trucks and buyers are spread over 20 regions by a Zipf law
  (`regionSkew: 1.0`: Budapest gets ~28%, the smallest regions ~1%), and buyers are scattered around their region
  centre with a Rayleigh distribution, so a few H3 cells are crowded and most are quiet.
- **Map-session churn.** A buyer opens the live map, watches for an exponentially distributed time (mean 60 s), closes
  it, and reopens it a moment later near the same spot. This continuously exercises subscription registration and
  cleanup in the hub, which long-lived static connections never do.
- **Proximity alerts do real work.** Every buyer favorites three trucks of their own region (Zipf-popular, so a few
  companies are watched by dozens of buyers) and saves two places near home. Without this, each telemetry batch finds no
  candidates and skips the most expensive part of ingestion.
- **Auth bursts** while everything else runs: 150 logins from distinct client addresses and 150 from one shared NAT
  address (which must allow exactly `authLimitPerMinute` and rate-limit the rest).
- Optional single-region worst case: `--profile hotspot` collapses every truck and buyer into Budapest.

## What it checks

It is a correctness test first and a measurement second; there are no invented latency SLOs.

- every telemetry batch is accepted with HTTP 202 and every live connection succeeds;
- no live event is ever outside the requested radius;
- **map convergence:** shortly after connecting, and again at the end, each open map must show every vehicle whose last
  accepted position is in radius and no vehicle that is not (a map is the fold of `location`/`offline` events, like
  a real client). This also covers enter/leave-radius transitions;
- the rate-limit contracts of the login bursts.

It reports request latency percentiles, HTTP status counts, **delivery latency** (telemetry request sent → position seen
on a viewer's stream; measured on one monotonic clock, so no clock-sync caveat), the live delivery ratio (below 100% means
the bounded subscriber buffer coalesced intermediate points), and **generator health**. If the generator's own scheduler
lag is high, the report warns that latencies are inflated by the load generator rather than the server.

## Start the disposable stack

The Postgres container has no persistent volume and is bound to loopback. Do not point this harness at production or
a database containing real user data.

```bash
docker run --rm --name mozgobolt-loadtest-postgres \
  -e POSTGRES_DB=mozgobolt_loadtest \
  -e POSTGRES_USER=loadtest \
  -e POSTGRES_PASSWORD=loadtest \
  -p 127.0.0.1:55432:5432 \
  -d postgres:18-alpine
until docker exec mozgobolt-loadtest-postgres pg_isready -U loadtest -d mozgobolt_loadtest; do sleep 1; done
```

Seed it and write test-only credentials under ignored `build/` output:

```bash
LOAD_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/mozgobolt_loadtest \
LOAD_TEST_DB_USER=loadtest \
LOAD_TEST_DB_PASSWORD=loadtest \
PASSWORD_PEPPER=local-load-test-only-pepper \
JWT_SECRET=local-load-test-jwt-secret-not-for-deployment \
JWT_ISSUER=mozgobolt-loadtest \
JWT_AUDIENCE=mozgobolt-loadtest \
./gradlew seedLiveLoadTestData
```

The seeder refuses non-loopback hosts and databases other than `mozgobolt_loadtest`. It seeds active access-token
families so the measured login burst is exactly 150 logins rather than setup noise. Seeded access tokens live 15
minutes: reseed (recreate the container) when they expire, and keep runs under 10 minutes. To reseed, stop the
container and start it again with the command above.

## Run

In one terminal, start the real application against that database (local-only credentials):

```bash
ENVIRONMENT=local HOST=127.0.0.1 PORT=18080 \
DB_URL=jdbc:postgresql://127.0.0.1:55432/mozgobolt_loadtest DB_USER=loadtest DB_PASSWORD=loadtest \
JWT_SECRET=local-load-test-jwt-secret-not-for-deployment \
JWT_ISSUER=mozgobolt-loadtest JWT_AUDIENCE=mozgobolt-loadtest \
PASSWORD_PEPPER=local-load-test-only-pepper \
./gradlew run
```

In another terminal:

```bash
./gradlew runLiveLoadTest                                     # city-skew profile, 180 s
./gradlew runLiveLoadTest --args="--profile hotspot"          # everything in Budapest: worst-case fan-out
./gradlew runLiveLoadTest --args="--login-burst false"        # steady state without the auth burst
./gradlew runLiveLoadTest --args="--viewer-session-seconds 0" # no map churn: connections stay open
./gradlew runLiveLoadTest --args="--truck-interval-seconds 1.2"
./gradlew runLiveLoadTest --args="--truck-interval-seconds 5 --points-per-batch 100"
```

Options: `--profile`, `--duration-seconds` (30–600), `--truck-interval-seconds`, `--points-per-batch`, `--radius-km`,
`--viewer-session-seconds` (0 disables churn), `--login-burst true|false`, `--seed`, `--base-url`, `--accounts`,
`--report`. The same seed reproduces the same routes and viewer behaviour. The runner refuses non-loopback hosts because
its synthetic `X-Forwarded-For` addresses (one trusted local proxy hop) are only valid for the disposable stack.

The app keeps each vehicle's last position in memory for five minutes and silently drops a telemetry point that implies an
impossible speed from it (it still answers 202). Re-running the *same* profile is fine, but after a run with a *different*
profile, restart the app or wait five minutes: before measuring, the runner checks that every truck's first position is
visible on a live map and stops with an explanatory error otherwise.

The process exits non-zero if any functional check fails. The JSON report is written to
`build/load-test/live-workload-report.json`.

## Interpreting results

Review request/delivery percentiles, status counts, the live delivery ratio, and the effective `DB_POOL_SIZE` and
`PASSWORD_HASH_CONCURRENCY`. Record resource evidence during each run:

```bash
docker exec mozgobolt-loadtest-postgres psql -U loadtest -d mozgobolt_loadtest -c \
  "select state, count(*) from pg_stat_activity where datname = 'mozgobolt_loadtest' group by state;"
docker exec mozgobolt-loadtest-postgres psql -U loadtest -d mozgobolt_loadtest -At -c \
  "select xact_commit from pg_stat_database where datname = 'mozgobolt_loadtest';"   # diff before/after = transactions per request
```

Also record app CPU/RSS and `docker stats mozgobolt-loadtest-postgres`. A single local run shares CPU between the
generator, the app, and PostgreSQL, so it is a reproducible baseline, not a capacity claim; run the generator from a
separate machine for deployment-capacity numbers.

## What it does not cover

Buyer REST traffic (listings, favorites, saved-location CRUD), vendor shift start/stop (`markOffline`), multi-instance
Redis relay, token refresh, and mixed map radii. Add them when those paths matter.
