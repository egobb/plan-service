# Snapshot Ingestion (`plan-service`)

- A **fetch worker** periodically downloads the provider **XML snapshot** and **stages** it in Postgres (DB-queue style).
- A **process worker** consumes staged rows in batches and **upserts** canonical `plans` in Postgres.
- The **API** exposes `GET /search` and serves results **strictly from Postgres** (never calling the provider at request time), keeping provider latency and provider failures outside the request path while allowing **historical** queries even if a plan disappears from the provider.

Tech: **Java 17**, **Spring Boot 3**, **Postgres**, Docker. Release history is in [`CHANGELOG.md`](CHANGELOG.md).

## Project links

- [Portfolio case study](https://enriquegoberna.com/projects/snapshot-ingestion/)
- [When Postgres Is Enough: Building a Resilient Snapshot Ingestion Pipeline Without Kafka](https://enriquegoberna.com/posts/when-postgres-is-enough-building-a-resilient-snapshot-ingestion-pipeline-without-kafka/)
- [Engineering portfolio](https://enriquegoberna.com/)

---

## Prerequisites

- Docker and the Docker Compose plugin (`docker compose version`). The default path builds and runs everything in containers.
- To run the test suite or build the jar on the host: a JDK 17 and the bundled Maven wrapper (`./mvnw`). Integration tests start Postgres via Testcontainers, so Docker is required for those too.

No external provider, cloud account, or broker is needed.

---

## Quick start (Docker Compose)

From a clean state:

```bash
docker compose -f deploy/docker-compose.yml down -v   # drop any previous volume
make run
```

`make run` builds one image and starts the whole stack with Docker Compose: Postgres, a
local demo provider (nginx serving a static XML snapshot), the `worker-fetch` and
`worker-process` workers, the `api`, a Traefik proxy, and Adminer. No external provider is
required.

The `worker-fetch` job polls on an interval (30s by default) and `worker-process` then
picks up the staged rows, so the first results appear **after the first ingestion cycle**,
usually within 30–60 seconds. Watch it happen with:

```bash
make logs        # Ctrl-C to stop tailing
```

Once a cycle has completed:

```bash
curl "http://localhost:18080/search?starts_at=2021-01-01T00:00:00Z&ends_at=2030-12-31T23:59:59Z"
```

With the bundled snapshot this returns **three** events. The snapshot also contains one
`offline` plan, which is stored but never returned by `/search` (see
[Pipeline walkthrough](#pipeline-walkthrough-fetch--stage--process--search)).

Stop the stack (keep data):

```bash
make stop
```

`make down` additionally removes the Postgres volume.

---

## Services and ports

| Service | URL / address | Notes |
|---|---|---|
| API | http://localhost:18080 | `GET /search`, `GET /actuator/*` |
| Postgres | `localhost:5432` | db `egobb`, user `egobb`, password `egobb` (local defaults) |
| Adminer (Postgres UI) | http://localhost:8081 | system `PostgreSQL`, server `db`, user/password/db `egobb` |
| Traefik dashboard | http://localhost:18081 | proxy routing view; not needed for normal use |
| Demo provider | `http://demo-provider/api/events` | reachable **only inside** the compose network |

With the default `make run` the API publishes host port `18080` directly. When you run the
scaled path (`make run-scaled`, see [Scaling](#scaling-local)) an overlay moves that host
binding to Traefik so multiple `api` replicas can share port `18080`.

---

## Pipeline walkthrough (fetch → stage → process → search)

A snapshot moves through three tables (`ingestion_runs`, `staging_plans`, `plans`) before it
is queryable. Most commands below run inside the `db` container; nothing here needs an
external tool.

1. **Fetch + stage.** `worker-fetch` acquires a per-provider advisory lock, streams the XML
   snapshot from the demo provider, and inserts rows into `staging_plans` under a new
   `ingestion_runs` row. The run moves `RUNNING → STAGED` when the whole snapshot has been
   staged (or `FAILED` if it broke mid-stream).

   ```bash
   docker compose -f deploy/docker-compose.yml exec -T db \
     psql -U egobb -d egobb -c \
     "select id, provider_id, status, staged_plans_count, started_at from ingestion_runs order by started_at desc limit 5;"
   ```

2. **Process.** `worker-process` claims batches of `PENDING` rows from `STAGED` runs using
   `SELECT ... FOR UPDATE SKIP LOCKED`, transforms each row, and upserts it into `plans`.
   Rows move `PENDING → PROCESSING → DONE` (or back to `PENDING`, then `FAILED`, on repeated
   errors).

   ```bash
   docker compose -f deploy/docker-compose.yml exec -T db \
     psql -U egobb -d egobb -c \
     "select status, count(*) from staging_plans group by status order by status;"
   ```

3. **Canonical data.** `plans` holds one row per plan in the snapshot. The bundled snapshot
   has four plans; three are `online` and one is `offline`.

   ```bash
   docker compose -f deploy/docker-compose.yml exec -T db \
     psql -U egobb -d egobb -c \
     "select provider_id, external_plan_id, title, ever_online, last_sell_mode from plans order by starts_at;"
   ```

4. **Search.** `/search` reads `plans` only and returns plans that were **ever** online
   (`ever_online = true`), so the `offline` plan is stored but not returned:

   ```bash
   curl "http://localhost:18080/search?starts_at=2021-01-01T00:00:00Z&ends_at=2030-12-31T23:59:59Z"
   ```

The sequence and state-machine diagrams live in [`docs/sequences.md`](docs/sequences.md) and
[`docs/staging-state-machine.md`](docs/staging-state-machine.md).

---

## Inspecting the database

Open a psql shell:

```bash
docker compose -f deploy/docker-compose.yml exec db psql -U egobb -d egobb
```

Useful queries:

```sql
-- recent ingestion runs
select * from ingestion_runs order by started_at desc limit 10;

-- staging backlog by state
select status, count(*) from staging_plans group by status order by status;

-- canonical rows
select provider_id, external_plan_id, title, ever_online, last_sell_mode,
       first_seen_at, last_seen_at
from plans order by starts_at;
```

Or use Adminer at http://localhost:8081 (server `db`, user/password/database `egobb`).

> `ingestion_runs.processed_plans_count` and `failed_plans_count` are reserved columns and
> are **not** currently maintained by the process worker; use the `staging_plans` state
> counts instead. See [`docs/data-model.md`](docs/data-model.md).

---

## Modes (single image)

This repo ships **one** Docker image and switches behaviour with `APP_MODE`:

- `APP_MODE=api` (default): runs the HTTP API
- `APP_MODE=worker-fetch`: downloads the provider snapshot and writes staged rows
- `APP_MODE=worker-process`: consumes staged rows and upserts canonical plans

In docker-compose all three are started (`api`, `worker-fetch`, `worker-process`). The
schema is created on startup by the `api` service; on a cold start the workers may briefly
log `relation "staging_plans" does not exist` until that has run, then recover on the next
poll.

---

## Multi-provider support

This implementation supports **N providers** (same XML schema) with a single shared DB.

- All tables contain `provider_id` and canonical plans use a **composite unique key**: `(provider_id, external_plan_id)`.
- When multiple providers are configured, the fetch worker loops them and uses a **per-provider distributed lock**.
- The process worker consumes a **shared staging queue** (it can process rows from any provider).

### Configuration

By default the app uses the single-provider configuration:

```yaml
provider:
  id: provider-1
  base-url: https://provider.example.com
  snapshot-path: /api/events
```

To enable multi-provider, set `providers.items` (it takes precedence over `provider.*`):

```yaml
providers:
  items:
    - id: snapshot-provider-a
      base-url: https://provider-a.example.com
      snapshot-path: /api/events
    - id: snapshot-provider-b
      base-url: https://provider-b.example.com
      snapshot-path: /api/events
```

> Note: you can also configure `provider.snapshot-url` (or the legacy `provider.url`) to pass the full URL directly. It takes precedence over base-url + snapshot-path.

---

## Scaling (local)

### Processing workers (verified)

`worker-process` is horizontally scalable **without a distributed lock**: it claims disjoint
batches with `SELECT ... FOR UPDATE SKIP LOCKED`, so adding replicas adds throughput with no
coordination service. A periodic requeue task moves stale `PROCESSING` rows back to
`PENDING` based on `claimed_at` to recover from worker crashes.

```bash
# defaults: API=2 PROCESS=2
make run-scaled

# custom counts
API=3 PROCESS=4 make run-scaled
```

To see scale-out draining a backlog, run the fetch worker fast and the process worker slow,
watch `staging_plans` grow, then add process replicas:

```bash
WORKER_FETCH_POLL_INTERVAL_MS=2000 WORKER_FETCH_BATCH_SIZE=2000 \
WORKER_PROCESS_POLL_INTERVAL_MS=15000 WORKER_PROCESS_BATCH_SIZE=50 \
API=1 PROCESS=1 make run-scaled

# ...observe backlog...
docker compose -f deploy/docker-compose.yml up -d --scale worker-process=3
```

The full version of this demo, with the queries to run at each step, is in
[`demo/demo-backlog-scale-out.md`](demo/demo-backlog-scale-out.md).

> `SKIP LOCKED` makes concurrent claiming safe by construction; a dedicated multi-worker
> contention test that asserts no row is processed twice is still open work
> (see [Current limitations](#current-limitations)).

### API replicas need a load balancer

Docker can scale `api` replicas, but a host port can only be published by one container. The
base compose file publishes the API on `18080` directly, so `--scale api=2` on that file
alone fails with:

> `Bind for 0.0.0.0:18080 failed: port is already allocated`

`make run-scaled` therefore layers [`deploy/docker-compose.scaled.yml`](deploy/docker-compose.scaled.yml),
which:

- removes the host port from `api` (replicas stay inside the compose network), and
- publishes Traefik's `web` entrypoint on host `18080` instead.

Traefik then load-balances `/search` and `/actuator` across the `api` replicas (give the
replicas a few seconds to finish booting; Traefik returns `502` until then). Plain
`make run` does not use the overlay, so its single-replica behaviour is unchanged.

> This Traefik setup is a local convenience to reproduce an L7-proxy topology. In a real
> deployment the service would sit behind an ingress / API gateway; the compose proxy is not
> a production configuration.

### DB guardrails (pool sizing)

Local scaling is *always* bounded by Postgres connections. The rule of thumb is:

```
(total_api_replicas       * api_pool_max)
+ (total_worker_fetch_replicas   * fetch_pool_max)
+ (total_worker_process_replicas * process_pool_max)
<= postgres_max_connections * safety_factor
```

Defaults in `deploy/docker-compose.yml` are intentionally conservative:

- API: `DB_POOL_MAX=5`
- worker-fetch: `DB_POOL_MAX=2`
- worker-process: `DB_POOL_MAX=3`

The bundled `postgres:16` uses its default `max_connections = 100`. Scaling to `API=3` and
`PROCESS=4` needs roughly `3*5 + 1*2 + 4*3 = 29` connections, comfortably inside that budget;
`worker-fetch` stays single-instance per provider (advisory lock).

---

## Failure and recovery

Both scenarios below use only the bundled synthetic snapshot.

### Crash recovery (stale-work requeue)

If a `worker-process` replica dies after claiming rows, those rows sit in `PROCESSING`
forever unless something requeues them. `RequeueStuckStagedPlansScheduled` (which runs
inside every `worker-process`) does exactly that: every `WORKER_PROCESS_REQUEUE_INTERVAL_MS`
it moves rows whose `claimed_at` is older than `WORKER_PROCESS_STUCK_TTL_MS` back to
`PENDING`, where a live worker picks them up again (the upsert is idempotent).

Processing the demo snapshot is sub-second, so racing a real `docker kill` almost never
leaves rows stuck. Instead, simulate a crashed claim directly in the database. Start the
stack with a short TTL:

```bash
WORKER_PROCESS_STUCK_TTL_MS=10000 WORKER_PROCESS_REQUEUE_INTERVAL_MS=5000 make run

# mark three rows as claimed-but-never-finished (as a dead worker would leave them)
docker compose -f deploy/docker-compose.yml exec -T db psql -U egobb -d egobb -c \
  "update staging_plans set status='PROCESSING', claimed_at = now() - interval '1 hour'
   where id in (select id from staging_plans order by created_at desc limit 3);"

# within ~15s the requeue task moves them back to PENDING, then a worker re-processes them
docker compose -f deploy/docker-compose.yml exec -T db psql -U egobb -d egobb -c \
  "select status, count(*) from staging_plans group by status order by status;"

# the requeue is logged (WARN) and counted
docker compose -f deploy/docker-compose.yml logs worker-process | grep "Requeued stuck"
docker compose -f deploy/docker-compose.yml exec -T worker-process \
  wget -qO- http://localhost:8080/actuator/prometheus | grep egobb_staging_requeued_total
```

The worker/ingestion metrics (`egobb_stage_*`, `egobb_process_*`, `egobb_scheduler_skips_*`,
`egobb_staging_requeued_total`) live on the **worker** actuators, not on the API at
`:18080` — hence the `docker compose exec ... worker-process` above.

### Retry budget

Processing failures are retried in place: `attempts` is incremented and the row goes back to
`PENDING`, up to `WORKER_PROCESS_MAX_ATTEMPTS` (`3` in `deploy/docker-compose.yml`; the
application default is `5` when the variable is unset), after which it becomes `FAILED` and
is left for inspection. The full state machine is in
[`docs/staging-state-machine.md`](docs/staging-state-machine.md).

### Fetch failures

`worker-fetch` uses bounded retry/backoff for provider errors. While the provider is
unreachable the API keeps serving the last canonical data from Postgres; ingestion resumes
on a later poll once the provider recovers.

---

## Resilience guardrails

### Bulkhead for `/search` (protects DB/pool)

`/search` is intentionally **DB-backed only**. Under load, the failure mode to avoid is
exhausting the DB connection pool.

This service applies a **bulkhead** (Resilience4j) that limits **in-flight** `/search`
requests. When the bulkhead is full, the API fails fast with `503` and error code
`TOO_BUSY`.

Configuration:

- `SEARCH_MAX_CONCURRENT_CALLS` (default `8`)
- `SEARCH_MAX_WAIT_DURATION` (default `0ms`)

Rule of thumb: keep `SEARCH_MAX_CONCURRENT_CALLS <= DB_POOL_MAX` (and usually a bit lower to
leave room for actuator/health and background tasks).

> In production, global rate limiting typically lives at the edge (API gateway / ingress).
> This bulkhead is a local guardrail to keep the service stable under load.

---

## Observability

- Actuator endpoints exposed: `health`, `info`, `metrics`, `prometheus`.
- Every container (`api`, `worker-fetch`, `worker-process`) serves its own
  `GET /actuator/prometheus` on port `8080`. Only the `api` port is published, so worker
  metrics are read with `docker compose -f deploy/docker-compose.yml exec <service> wget -qO- http://localhost:8080/actuator/prometheus`.
- Structured JSON logs are supported (toggle via env in the repo).

Custom domain metrics include:

- Provider fetch outcomes and duration (`egobb_provider_snapshot_*`, `egobb_stage_snapshot_*` — `worker-fetch`)
- Staged inserts (batch sizes) (`egobb_staging_insert_batch_*` — `worker-fetch`)
- Processing throughput/failures (`egobb_process_plan_*` — `worker-process`)
- Re-queued stuck rows (`egobb_staging_requeued_total` — `worker-process`)
- Scheduler skips, e.g. fetch lock contention (`egobb_scheduler_skips_total` — workers)
- `/search` rejects when the bulkhead is full (`egobb_search_rejected_total{reason="bulkhead_full"}` — `api`)

More detail in [`docs/observability.md`](docs/observability.md).

---

## Operational signals and response paths

The current metrics naturally split into two operational surfaces: the **public API** and **ingestion freshness**. This repository does not publish a measured production SLO or latency baseline yet.

For the API, the relevant signals are `http.server.requests` for `/search` plus the custom `search_rejected_total{reason="bulkhead_full"}`, which shows when the local bulkhead is protecting the database from additional in-flight work.

For ingestion, provider fetch outcomes/duration, processing throughput/failures, and the age of the oldest `PENDING` staged row expose whether the pipeline is falling behind. If provider requests fail repeatedly, bounded retry/backoff avoids aggressive retries while the API continues serving the last canonical data from Postgres. The response path is to distinguish network/5xx/parse failures, back off or pause `worker-fetch` if needed, and resume once the provider boundary recovers.

If staging lag grows, `worker-process` can be scaled horizontally via `SKIP LOCKED`, subject to PostgreSQL connection headroom and contention. If bulkhead rejects increase, the useful next step is to inspect database saturation and measured request behavior before changing concurrency or pool limits.

---

## Running tests

```bash
make test        # ./mvnw -f app/pom.xml -B verify
```

This runs:

- **Surefire** unit tests (`*Test`) — domain rules, query/cache boundaries, services, adapters with mocks.
- **Failsafe** integration tests (`*IT`) — real Postgres via **Testcontainers**:
  `PlanPostgresAdapterIT`, `SearchControllerIT`, `SearchControllerErrorIT`,
  `SearchControllerTimezoneIT`, `StagingClaimGatingByRunStatusIT`, `StagingProcessingIT`.

Unit tests only:

```bash
./mvnw -f app/pom.xml -B test
```

Docker must be available for the integration tests. Test strategy and coverage notes are in
[`docs/testing.md`](docs/testing.md).

> The Docker image build (`make run`) uses its own Maven cache, so a custom host
> `~/.m2/settings.xml` only affects host-side `make test` / `make build`, not the Compose
> path.

---

## Current limitations

Demonstrated behaviour and production recommendations are kept separate on purpose. Current
gaps:

- **API-replica scaling is a local convenience, not a topology.** It works only through
  `make run-scaled` and the Compose overlay's single-node Traefik; there is no real ingress.
- **Cold-start noise.** Only the `api` service initialises the schema, so on a fresh
  `down -v` start the workers log `relation ... does not exist` for a poll or two before
  recovering.
- **Item-by-item upserts.** Workers claim rows in batches but upsert one row at a time. This
  keeps failure isolation and idempotency simple; it is not tuned for maximum throughput.
- **No benchmark yet.** There is no reproducible throughput / latency / streaming-memory
  measurement in this repo.
- **`SKIP LOCKED` contention and repeat-ingestion idempotency are not yet asserted by
  tests.**
- **No dead-letter workflow or provider circuit breaker.** `FAILED` staging rows are simply
  left for inspection.
- `ingestion_runs.processed_plans_count` / `failed_plans_count` are reserved and unused.

The design deliberately stays on Postgres: the provider is a periodic **snapshot**, not an
event stream, so a DB-queue with advisory locks and `SKIP LOCKED` is sufficient and a broker
is not required by default. See the [Design rationale](#design-rationale-and-trade-offs) and
the "When Postgres Is Enough" article.

---

## Deeper documentation

- [`docs/architecture.md`](docs/architecture.md) — component overview and responsibilities
- [`docs/data-model.md`](docs/data-model.md) — tables and the staging/canonical split
- [`docs/sequences.md`](docs/sequences.md) — search, staging and processing flows
- [`docs/staging-state-machine.md`](docs/staging-state-machine.md) — staged-row lifecycle
- [`docs/performance.md`](docs/performance.md) — why `/search` stays predictable, scaling knobs
- [`docs/observability.md`](docs/observability.md) — metrics, logs, correlation
- [`docs/security.md`](docs/security.md) — XML parsing hardening and boundaries
- [`docs/testing.md`](docs/testing.md) — test pyramid and how to run each layer
- [`docs/adr/`](docs/adr/) — decision records (DB-queue staging, advisory-lock fetch, monotonic upsert)
- [`demo/advisory-lock-demo.md`](demo/advisory-lock-demo.md) — per-provider fetch locking, hands-on
- [`demo/demo-backlog-scale-out.md`](demo/demo-backlog-scale-out.md) — backlog + `SKIP LOCKED` scale-out, hands-on

---

## Design rationale (and trade-offs)

### Why Postgres + DB-queue staging instead of Kafka?

- The provider is a **snapshot** input, not a continuous event stream. A DB-queue is enough and keeps infra minimal.
- Staging tables provide **auditability** (`ingestion_runs`) and deterministic retries.
- Trade-off: DB becomes the concurrency bottleneck if you push throughput very high; Kafka scales better for very large fan-out.

### Why API never calls the provider?

- Keeps provider latency and provider failures outside the request path.
- Enables historical queries even when a plan disappears from the provider snapshot.
- Trade-off: freshness depends on polling interval and ingestion health.

### Why `ever_online` sticky + `last_sell_mode`?

- The service is designed to return historical plans even if they later become offline.
- Sticky `ever_online` makes search semantics stable while still tracking the latest sell mode.

Note: workers claim rows in batches using `FOR UPDATE SKIP LOCKED`, but upserts are currently applied item-by-item. This preserves failure isolation and idempotency, and keeps behavior deterministic under retries.
