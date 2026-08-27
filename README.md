# Snapshot Ingestion (`plan-service`) — v0.10.0

- A **fetch worker** periodically downloads the provider **XML snapshot** and **stages** it in Postgres (DB-queue style).
- A **process worker** consumes staged rows in batches and **upserts** canonical `plans` in Postgres.
- The **API** exposes `GET /search` and serves results **strictly from Postgres** (never calling the provider at request time), keeping provider latency and provider failures outside the request path while allowing **historical** queries even if a plan disappears from the provider.

Tech: **Java 17**, **Spring Boot 3**, **Postgres**, Docker.

## Project links

- [Portfolio case study](https://enriquegoberna.com/projects/snapshot-ingestion/)
- [When Postgres Is Enough: Building a Resilient Snapshot Ingestion Pipeline Without Kafka](https://enriquegoberna.com/posts/when-postgres-is-enough-building-a-resilient-snapshot-ingestion-pipeline-without-kafka/)
- [Engineering portfolio](https://enriquegoberna.com/)

---

## Quick start (Docker Compose)

```bash
make run
```

This starts Postgres, a local demo provider (static XML snapshot), the fetch worker,
the process worker, and the API. No external provider is required.

Once started:

```bash
curl "http://localhost:18080/search?starts_at=2021-01-01T00:00:00Z&ends_at=2030-12-31T23:59:59Z"
```

Stop:

```bash
make stop
```

---

## Services and ports

- API (via local load balancer): http://localhost:18080
- Postgres: localhost:5432 (db name `egobb`, user `egobb`, password `egobb` by default)
- Adminer (Postgres UI): http://localhost:8081
- Demo provider (snapshot source inside compose network): http://demo-provider/api/events

> Note: when running **scaled** (`make run-scaled`), the `api` containers **do not bind host ports**. Only the load balancer binds `18080` and routes traffic to the `api` replicas.

---

## Modes (single image)

This repo ships **one** Docker image and switches behaviour with `APP_MODE`:

- `APP_MODE=api` (default): runs the HTTP API
- `APP_MODE=worker-fetch`: downloads the provider snapshot and writes staged rows
- `APP_MODE=worker-process`: consumes staged rows and upserts canonical plans

In docker-compose all three are started (`api`, `worker-fetch`, `worker-process`).

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

## Resilience guardrails

### 1) Bulkhead for `/search` (protects DB/pool)

`/search` is intentionally **DB-backed only**. Under load, the failure mode you want to avoid is exhausting the DB connection pool.

This service applies a **bulkhead** (Resilience4j) that limits **in-flight** `/search` requests. When the bulkhead is full, the API fails fast with `503` and an error code `TOO_BUSY`.

Configuration:

- `SEARCH_MAX_CONCURRENT_CALLS` (default `8`)
- `SEARCH_MAX_WAIT_DURATION` (default `0ms`)

Rule of thumb: keep `SEARCH_MAX_CONCURRENT_CALLS <= DB_POOL_MAX` (and usually a bit lower to leave room for actuator/health and background tasks).

> In production, global rate limiting typically lives at the edge (API gateway / ingress). This bulkhead is a local guardrail to keep the service stable under load.

---

## Scaling (local)

The process worker is horizontally scalable **without a distributed lock**: it uses a DB-queue pattern with
`SELECT ... FOR UPDATE SKIP LOCKED`. A periodic requeue task moves stale `PROCESSING` rows back to `PENDING`
based on `claimed_at` to recover from worker crashes.

### Why scaling the API needs a load balancer

Docker can scale `api` replicas, but **host ports cannot be published by multiple containers**. If `api` is configured
with `ports: "8080:8080"` and you try to scale to 2 replicas, the second one fails with:

> `Bind for 0.0.0.0:8080 failed: port is already allocated`

To support `api` scaling locally, the docker-compose setup includes a lightweight **reverse proxy / load balancer** (Traefik):

- Only Traefik publishes host port `18080`.
- `api` replicas are reachable **only inside** the compose network.
- Traefik routes incoming traffic to the replicas.

### One command: scale API and processing

```bash
# defaults: API=2 PROCESS=2
make run-scaled

# custom
API=3 PROCESS=4 make run-scaled
```

Under the hood:

```bash
docker compose -f deploy/docker-compose.yml up -d --build --scale api=<API> --scale worker-process=<PROCESS>
```

### DB guardrails (pool sizing)

Local scaling is *always* bounded by Postgres connections.

The rule of thumb is:

```
(total_api_replicas * api_pool_max)
+ (total_worker_fetch_replicas * fetch_pool_max)
+ (total_worker_process_replicas * process_pool_max)
<= postgres_max_connections * safety_factor
```

Defaults in `docker-compose.yml` are intentionally conservative:

- API: `DB_POOL_MAX=5`
- worker-fetch: `DB_POOL_MAX=2`
- worker-process: `DB_POOL_MAX=3`

If you scale to `API=3` and `PROCESS=4`, the total max connections is roughly:

- `3*5 + 1*2 + 4*3 = 29`

---

## Observability

- Actuator endpoints exposed: `health`, `metrics`, `prometheus`.
- Prometheus endpoint: `GET /actuator/prometheus`.
- Structured JSON logs are supported (toggle via env in the repo).

Custom domain metrics include:

- Provider fetch outcomes and duration
- Staged inserts (batch sizes)
- Processing throughput/failures
- Re-queued stuck rows
- `/search` rejects when the bulkhead is full

---

## Operational signals and response paths

The current metrics naturally split into two operational surfaces: the **public API** and **ingestion freshness**. This repository does not publish a measured production SLO or latency baseline yet.

For the API, the relevant signals are `http.server.requests` for `/search` plus the custom `search_rejected_total{reason="bulkhead_full"}`, which shows when the local bulkhead is protecting the database from additional in-flight work.

For ingestion, provider fetch outcomes/duration, processing throughput/failures, and the age of the oldest `PENDING` staged row expose whether the pipeline is falling behind. If provider requests fail repeatedly, bounded retry/backoff avoids aggressive retries while the API continues serving the last canonical data from Postgres. The response path is to distinguish network/5xx/parse failures, back off or pause `worker-fetch` if needed, and resume once the provider boundary recovers.

If staging lag grows, `worker-process` can be scaled horizontally via `SKIP LOCKED`, subject to PostgreSQL connection headroom and contention. If bulkhead rejects increase, the useful next step is to inspect database saturation and measured request behavior before changing concurrency or pool limits.

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