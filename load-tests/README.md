# Live workload test

An opt-in, end-to-end workload against a real local Ktor/Netty process and an isolated PostgreSQL database. It is
written in Kotlin (coroutines + `Flow`) and lives in the test source set (`src/test/kotlin/com/mozgobolt/loadtest`), so
it shares the project's ktlint/detekt gates, the wire DTOs, and the Kotlin seeder. It drives only public HTTP: login,
vendor telemetry, and the buyer nearby SSE stream.

## What it models

Fixture limits and profiles come from `workload.json`, which the seeder and runner both read.

- **Up to 200 trucks**, each a vendor with a consented active vehicle assignment. Trucks are seeded evenly across 20
  Hungarian regional centres; the default `city-skew` profile uses 100. Each drives a closed 9 km loop at 15–45 km/h
  through many H3 cells and in and out of viewers' radii. Uploads default to one point every 5 s, staggered across the
  interval, with one upload in flight per truck. Stress profiles can synchronize trucks so batches start together.
- **500 buyers** with a Zipf-skewed geography (Budapest gets about 28%; the smallest regions about 1%), scattered around
  their region centre with a Rayleigh distribution. This creates a few crowded H3 areas and many quiet ones.
- **Map-session churn.** A buyer opens the live map, watches for an exponentially distributed time (mean 60 s), closes
  it, and reopens it a moment later near the same spot. This continuously exercises subscription registration and
  cleanup in the hub, which long-lived static connections never do.
- **Proximity alerts do real work.** Every buyer favorites three trucks of their own region (Zipf-popular, so a few
  companies are watched by dozens of buyers) and saves two places near home. Without this, each telemetry batch finds no
  candidates and skips the most expensive part of ingestion.
- **Auth bursts** while everything else runs: 150 logins from distinct client addresses and 150 from one shared NAT
  address (which must allow exactly `authLimitPerMinute` and rate-limit the rest).
- `city-skew`: 100 trucks and all 500 buyers in the seeded regional distribution.
- `hotspot`: 200 trucks and 500 buyers moved into the Budapest area, stressing city-scale fan-out.
- `country-wide`: 200 trucks and 200 buyers selected round-robin across all regions; actors retain seeded locations.
- `single-cell`: 200 trucks loop within 100 m of one H3 resolution-7 cell centre, and all 200 viewers watch that same
  centre. Use `--viewers 500` to include the full buyer fixture.

## What it checks

It is a correctness test first and a measurement second; there are no invented latency SLOs.

- every telemetry batch is accepted with HTTP 202 and every live connection succeeds;
- only batches scheduled within the run window are sent, and final-map checks wait for those HTTP requests to finish,
  then allow a 10 s live-event drain;
- static-session runs fail if the requested number of connections is not concurrently established; the report records
  the peak successfully connected stream count;
- no live event is ever outside the requested radius;
- **map convergence:** shortly after connecting, and again at the end, each open map must show every vehicle whose last
  accepted position is in radius and no vehicle that is not (a map is the fold of `location`/`offline` events, like
  a real client). This also covers enter/leave-radius transitions;
- the rate-limit contracts of the login bursts;
- `/health` and `/ready` throughout the run, not only before traffic starts.

It reports request latency percentiles, HTTP status counts, **delivery latency** (telemetry request sent → position seen
on a viewer's stream; measured on one monotonic clock, so no clock-sync caveat), the live delivery ratio (below 100% means
the bounded subscriber buffer coalesced intermediate points), peak concurrent SSE connections, and **generator health**.
If the generator's own scheduler lag is high, the report warns that latencies are inflated by the load generator rather
than the server.

## Two-node relay capacity run

The same runner can drive a two-process topology: `--base-url` is node A, all telemetry is posted to node A, and
viewers are distributed evenly across node A and node B. Its report requires the requested static SSE connections to
be concurrently established on **each** node and records successful and peak connections per node. The fleet/map
convergence checks and delivery ratio then verify that node B receives telemetry published through node A.

Start a disposable Valkey alongside the PostgreSQL stack above:

```bash
docker run --rm --name mozgobolt-relay-loadtest-valkey \
  -p 127.0.0.1:56392:6379 \
  -d valkey/valkey:9.2.0-alpine
```

Build the application distribution once:

```bash
./gradlew --no-daemon installDist
```

In two terminals, start both app processes against the same disposable database and Valkey. Keep the JWT and password
secrets identical across nodes; `REDIS_URL` must be set on every node. The same shared Valkey backs both live-map/ping
relay and atomic, cluster-wide rate-limit buckets. With `REDIS_URL` blank, the relay and rate limits are process-local;
that configuration is for single-instance use only. `/ready` checks PostgreSQL and, when configured, Valkey; rate-limited
requests fail closed with HTTP 503 if the shared limiter is unavailable. It also checks the relay connections used for
cross-node live delivery. Metrics expose the Hikari pool gauges for sampling:

Production replicas must also use the same S3-compatible object store (`OBJECT_STORAGE_ENABLED=true`, same endpoint and
bucket); the default `LocalDiskImageStorage` is process-local, so an image uploaded on one instance is not available
from another. This live-map workload does not test image-storage convergence.

```bash
JAVA_TOOL_OPTIONS=-Duser.timezone=UTC \
ENVIRONMENT=local HOST=127.0.0.1 PORT=18080 \
DB_URL=jdbc:postgresql://127.0.0.1:55432/mozgobolt_loadtest DB_USER=loadtest DB_PASSWORD=loadtest \
JWT_SECRET=local-load-test-jwt-secret-not-for-deployment \
JWT_ISSUER=mozgobolt-loadtest JWT_AUDIENCE=mozgobolt-loadtest \
PASSWORD_PEPPER=local-load-test-only-pepper \
REDIS_URL=redis://127.0.0.1:56392 METRICS_ENABLED=true DB_POOL_SIZE=10 \
./build/install/mozgobolt/bin/mozgobolt
```

Use the same command in the second terminal with `PORT=18081`. Wait until both `/health` and `/ready` return HTTP 200.
Before the run, confirm that both app processes subscribed to the location relay:

```bash
docker exec mozgobolt-relay-loadtest-valkey valkey-cli PUBSUB NUMSUB mozgobolt:vehicle-locations
```

The subscriber count should be `2`. Before serving requests, each process waits for its location subscription and
restores recent (five-minute), active, consented vehicle positions from one read-only PostgreSQL repeatable-read
snapshot. The query filters assignments in SQL rather than binding one parameter per active assignment. Each process
then reconciles that in-memory snapshot every 30 seconds; after a Pub/Sub gap, persisted positions are repaired after
the history writer flushes and the next reconciliation runs. Pub/Sub itself is volatile and does not replay events.

The history writer also makes a bounded final flush during graceful shutdown. An abrupt crash, a full 50,000-point
buffer, database unavailability, or a timed-out shutdown flush can still lose telemetry history. Live positions are
delivered independently, but only persisted positions can seed a restarted instance. Vehicle-ping SSE and push are
post-commit best-effort notifications: `vehicle_pings` stores cooldown state, not the coarsened buyer location, so a
missed ping cannot be replayed after a disconnect or process crash. This is intentional for privacy; durable ping
delivery would require a separate product decision on retaining the notification payload.

Each process also has its own Hikari pool (default max 10). Budget the total as
`replica_count × DB_POOL_SIZE + migrations/other clients` under PostgreSQL's connection limit, leaving capacity for
administrative access; configure `DB_POOL_SIZE` per replica, not per deployment. Each live-map SSE connection retains
subscriber state proportional to the vehicles visible to it; that state is removed when the flow is cancelled. The
application does not impose a hard concurrent-SSE cap, so set ingress limits from measured per-replica memory and
connection capacity rather than assuming an arbitrary safe number. Then run 20 single-cell vehicles and 200 static
viewers, split 100/100:

```bash
./gradlew --no-daemon runLiveLoadTest --args="--profile single-cell --node-b-url http://127.0.0.1:18081 --trucks 20 --viewers 200 --duration-seconds 30 --truck-interval-seconds 1.2 --viewer-connect-ramp-up-seconds 0 --viewer-session-seconds 0 --synchronize-trucks true --login-burst false --report build/load-test/two-node-relay-report.json"
```

The JSON report includes connection successes and peak concurrent streams per node, event delivery ratio/latency, HTTP
statuses, map-convergence failures, and load-generator scheduler lag. The synthetic workload publishes only through
node A; connections on node B therefore exercise the cross-process relay rather than an in-process test double.

While the run is active, sample the server-side pool and disposable database without changing the configured pool size:

```bash
for sample in $(seq 1 60); do
  date -u +%H:%M:%S
  for port in 18080 18081; do
    echo "node-port=$port"
    curl -fsS "http://127.0.0.1:$port/metrics" |
      grep -E '^hikaricp_connections_(active|idle|pending|max|min)'
  done
  docker exec mozgobolt-relay-loadtest-valkey valkey-cli PUBSUB NUMSUB mozgobolt:vehicle-locations
  sleep 1
done
docker exec mozgobolt-loadtest-postgres psql -U loadtest -d mozgobolt_loadtest -c \
  "select state, count(*) from pg_stat_activity where datname = 'mozgobolt_loadtest' group by state;"
```

Record app CPU/RSS with the platform process monitor (on macOS, `top -l 60 -s 1 -pid <app-pid> -stats pid,cpu,rsize`)
and JVM GC counters with `jstat -gc <app-pid> 1s 60` in parallel with the run. These resource samples are machine-specific;
compare them with a single-node run on the same host and workload, and do not treat a shared-host run as
deployment-capacity evidence. Stop only the named disposable Valkey/PostgreSQL containers afterward; they are launched
with `--rm`.

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
JAVA_TOOL_OPTIONS=-Duser.timezone=UTC \
LOAD_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/mozgobolt_loadtest \
LOAD_TEST_DB_USER=loadtest \
LOAD_TEST_DB_PASSWORD=loadtest \
PASSWORD_PEPPER=local-load-test-only-pepper \
JWT_SECRET=local-load-test-jwt-secret-not-for-deployment \
JWT_ISSUER=mozgobolt-loadtest \
JWT_AUDIENCE=mozgobolt-loadtest \
./gradlew --no-daemon seedLiveLoadTestData
```

The seeder refuses non-loopback hosts and databases other than `mozgobolt_loadtest`. It seeds 200 vendors/active
vehicles and 500 buyers, plus active access-token families so the measured login burst is exactly 150 logins rather
than setup noise. Seeded access tokens live 15 minutes: reseed (recreate the container) when they expire, and keep runs
under 10 minutes. To reseed, stop the container and start it again with the command above.

To keep multiple disposable runs separate, set `LOAD_TEST_ACCOUNTS_OUTPUT` to a unique CSV path under
`build/load-test/`; the seeder refuses paths outside that ignored build directory. The default remains
`build/load-test/users.csv`.

The current Exposed `timestamp()` mapping stores `Instant` values in PostgreSQL `TIMESTAMP WITHOUT TIME ZONE`. Keep the
seeder and app JVMs on the same timezone; these commands pin both to UTC so a host seeder can safely target the UTC
container runtime. `--no-daemon` avoids reusing a Gradle JVM started in another timezone. Do not mix a non-UTC host
seeder with a UTC app container.

## Run

In one terminal, start the real application against that database (local-only credentials):

```bash
JAVA_TOOL_OPTIONS=-Duser.timezone=UTC \
ENVIRONMENT=local HOST=127.0.0.1 PORT=18080 \
DB_URL=jdbc:postgresql://127.0.0.1:55432/mozgobolt_loadtest DB_USER=loadtest DB_PASSWORD=loadtest \
JWT_SECRET=local-load-test-jwt-secret-not-for-deployment \
JWT_ISSUER=mozgobolt-loadtest JWT_AUDIENCE=mozgobolt-loadtest \
PASSWORD_PEPPER=local-load-test-only-pepper \
./gradlew --no-daemon run
```

In another terminal:

```bash
./gradlew runLiveLoadTest # city-skew: 100 trucks, 500 buyers, 180 s
./gradlew runLiveLoadTest --args="--profile hotspot" # 200 trucks, 500 viewers, Budapest-area fan-out
./gradlew runLiveLoadTest --args="--profile country-wide --duration-seconds 60 --truck-interval-seconds 1.2 --viewer-connect-ramp-up-seconds 0 --viewer-session-seconds 0 --synchronize-trucks true --login-burst false"
./gradlew runLiveLoadTest --args="--profile single-cell --duration-seconds 30 --truck-interval-seconds 1.2 --viewer-connect-ramp-up-seconds 0 --viewer-session-seconds 0 --synchronize-trucks true --login-burst false"
./gradlew runLiveLoadTest --args="--profile single-cell --viewers 500 --duration-seconds 30 --truck-interval-seconds 5 --viewer-connect-ramp-up-seconds 0 --viewer-session-seconds 0 --synchronize-trucks true --login-burst false"
./gradlew runLiveLoadTest --args="--login-burst false"        # steady state without the auth burst
./gradlew runLiveLoadTest --args="--viewer-session-seconds 0" # no map churn: connections stay open
./gradlew runLiveLoadTest --args="--truck-interval-seconds 1.2 --duration-seconds 30 --viewers 200"
./gradlew runLiveLoadTest --args="--truck-interval-seconds 5 --points-per-batch 100 --duration-seconds 30 --viewers 20"
```

Options: `--profile`, `--trucks` (1–200), `--viewers` (1–500), `--duration-seconds` (30–600),
`--truck-interval-seconds`, `--points-per-batch`, `--synchronize-trucks true|false`,
`--viewer-connect-ramp-up-seconds` (0–60), `--radius-km`, `--viewer-session-seconds` (0 disables churn),
`--login-burst true|false`, `--seed`, `--base-url`, optional `--node-b-url`, `--accounts`, `--report`. The same seed
reproduces routes, regional selection, and viewer behaviour. Safety limits cap a run at 1,000,000 telemetry points and 4,000,000
point/viewer comparisons. The runner refuses non-loopback hosts because its synthetic `X-Forwarded-For` addresses (one
trusted local proxy hop) are only valid for the disposable stack.

The app keeps each vehicle's last position in memory for five minutes and silently drops a telemetry point that implies an
impossible speed from it (it still answers 202). Re-running the *same* profile is fine, but after a run with a *different*
profile, restart the app or wait five minutes: before measuring, the runner checks that every truck's first position is
visible on a live map and stops with an explanatory error otherwise.

The process exits non-zero if any functional check fails. The JSON report is written to
`build/load-test/live-workload-report.json`.

## Interpreting results

Review request/delivery percentiles, status counts, peak concurrent connections, live delivery ratio, and effective
`DB_POOL_SIZE` and
`PASSWORD_HASH_CONCURRENCY`. Record resource evidence during each run:

```bash
docker exec mozgobolt-loadtest-postgres psql -U loadtest -d mozgobolt_loadtest -c \
  "select state, count(*) from pg_stat_activity where datname = 'mozgobolt_loadtest' group by state;"
docker exec mozgobolt-loadtest-postgres psql -U loadtest -d mozgobolt_loadtest -At -c \
  "select xact_commit from pg_stat_database where datname = 'mozgobolt_loadtest';"   # diff before/after = transactions per request
```

Also record app CPU/RSS and `docker stats mozgobolt-loadtest-postgres`. The nationwide profile uses 20 regional city
centres with short local loops; it is not a road-network simulation of long-haul routes between cities. A single local
run shares CPU between the generator, app, and PostgreSQL, so it is a reproducible stress baseline, not a
production-capacity claim; run the generator from a separate machine for deployment-capacity measurements.

## What it does not cover

Buyer REST traffic (listings, favorites, saved-location CRUD), vendor shift start/stop (`markOffline`), sustained
token refresh, and mixed map radii are not part of this workload. The optional two-node mode covers sustained location
relay fan-out for one local topology, while the separate real-Valkey regression test covers relay delivery/reconnect
correctness. Add the remaining traffic patterns when those paths need capacity evidence.
