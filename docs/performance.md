# Performance

This document explains why `GET /search` remains fast and predictable, and what the main performance levers are.

## Core principle: `/search` is provider-independent
The API never calls the external provider at request time. Requests are served strictly from Postgres, so search latency depends on:

- SQL query cost
- Postgres resources (CPU, memory, I/O)
- Connection pool saturation

This design keeps API latency stable even when the provider is slow or down.

## Query shape
`/search` runs a simple query:

- Filters by `ever_online = TRUE`
- Applies optional `starts_at` and `ends_at` bounds
- Orders by `starts_at` and paginates with `LIMIT/OFFSET`

The implementation uses **dynamic SQL** (only adds predicates when the parameter is present), avoiding Postgres issues with NULL parameter types.

## Caching (hot ranges)
To reduce repeated DB reads for common requests, the query handler caches "hot" searches:

- Only when `offset == 0` and `limit <= 100`
- Cache key derived from query string representation
- Empty results are not cached

This is intentionally conservative: it reduces load under repeated popular queries without risking high memory usage.

## Database indexes
Current schema adds indexes on:

- `plans(starts_at)`
- `plans(ever_online)`
- `plans(provider_id, external_plan_id)` unique key
- `staging_plans(provider_id, status, attempts, created_at)`

### Recommended extra index (multi-provider / shared queue)
When `worker-process` claims work **across providers** (shared queue), the claim query filters by `status` and `attempts` and orders by `created_at`. The existing index starts with `provider_id`, so it is not optimal for the "any provider" path.

Recommended additional index:

```sql
CREATE INDEX IF NOT EXISTS ix_staging_plans_pick_any
  ON staging_plans(status, attempts, created_at);
```

This improves throughput when multiple providers are configured.

## Scaling knobs

### API throughput (local scaling)
- Scale `api` horizontally.
- When scaling `api` replicas under Docker Compose, **do not publish host ports on `api`**. Host port `8080` can only be bound once.
- Use the compose-provided load balancer (Traefik) to bind `8080` and route to `api` replicas inside the network.
- Keep DB pool small per replica (`DB_POOL_MAX`) to avoid saturating Postgres with too many connections.

In production, you’d typically front the service with an ingress / gateway / L7 proxy anyway; Traefik is only a local convenience to simulate that topology.

### Ingestion throughput
- Scale `worker-process` horizontally (safe via `FOR UPDATE SKIP LOCKED`).
- Tune `WORKER_PROCESS_BATCH_SIZE` to balance latency vs transaction overhead.
- Keep `worker-fetch` single-instance per provider (guarded by advisory lock) and tune `WORKER_FETCH_BATCH_SIZE`.

## Guardrails (Postgres connections)
The effective upper bound for local scaling is often **max DB connections**.

Rule of thumb:

```
(api_replicas * api_pool_max)
+ (fetch_replicas * fetch_pool_max)
+ (process_replicas * process_pool_max)
<= postgres_max_connections * safety_factor
```

Defaults are conservative to keep latency stable under scaling.

## Measuring and troubleshooting

- Use the Prometheus endpoint: `GET /actuator/prometheus`
- Track API latency via `http_server_requests_seconds` (p95/p99 for `/search`).
- Track pipeline throughput and failures via custom metrics (see `docs/observability.md`).

If `/search` latency grows:

1. Check connection pool saturation (threads waiting for a connection).
2. Check slow query logs / `EXPLAIN ANALYZE` for the search query.
3. Reduce worker pressure (batch size / replicas) or add CPU/IO to Postgres.
4. Add/adjust indexes (as above).
