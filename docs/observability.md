# Observability

This document explains what to watch (SLIs), what the custom metrics mean, and what to do when things go wrong.

## Quick links

- Health: `GET /actuator/health`
- Metrics (Prometheus): `GET /actuator/prometheus`

### Useful env vars

- `LOG_FORMAT=json` → structured JSON logs (otherwise plain text)
- `SEARCH_SLOW_LOG_MS=200` → only log `/search` access lines when **slow** (>= threshold) or **5xx**
- `app.mode` (service mode): `api`, `worker-fetch`, `worker-process`

## What to watch (SLIs)

### API SLIs (user-facing)
1. **Latency** for `/search`
   - Watch `http_server_requests_seconds` (p95/p99) filtered by `uri="/search"` (or the framework’s templated URI).
   - Goal: stable latency independent from provider behavior (API only hits Postgres).

2. **Error rate**
   - Watch 5xx rate via `http_server_requests_seconds_count` grouped by `status`.
   - Goal: 0 sustained 5xx; short spikes only during deployments.

3. **Saturation signals**
   - Rising latency + stable request volume often indicates DB pressure or slow queries.
   - Use connection pool metrics (if enabled by Spring Boot) + DB logs as next step.

### Pipeline SLIs (ingestion correctness + throughput)
4. **Ingestion rate vs processing rate**
   - Ingestion: `egobb_stage_snapshot_total` / `egobb_provider_snapshot_total`
   - Processing: `egobb_process_plan_total`
   - Goal: processing keeps up with staging over time (no unbounded backlog).

5. **Pipeline error rate**
   - Watch `*_total{outcome="error"}` vs `*_total{outcome="success"}`.

6. **Staging lag / backlog (proxy)**
   - If ingestion continues but processing rate drops, backlog grows.
   - Proxy signals:
     - `rate(egobb_stage_snapshot_total[5m])` > `rate(egobb_process_plan_total[5m])` sustained
     - requeue activity increases: `rate(egobb_staging_requeued_total[10m])` rising

## Custom metrics glossary (low-cardinality)

> All custom metrics are designed to stay low-cardinality (no plan IDs, no titles, etc.).

### Provider / snapshot fetch
- `egobb_provider_snapshot_total{provider_id, outcome}`
  - Counts snapshot fetch attempts (success/error).
- `egobb_provider_snapshot_duration_seconds{provider_id}`
  - Timer for fetch + stream acquisition time.

### Staging (DB queue insert)
- `egobb_stage_snapshot_total{provider_id, outcome}`
  - Counts staging runs (success/error).
- `egobb_stage_snapshot_duration_seconds{provider_id}`
  - End-to-end staging run duration.
- `egobb_staging_insert_batch_total{provider_id}`
  - Number of staging insert batches.
- `egobb_staging_insert_batch_size{provider_id}` (distribution summary)
  - Batch sizes used while inserting into staging.

### Processing (staging → final tables)
- `egobb_process_plan_total{provider_id, outcome}`
  - Counts plan upserts processed (success/error).
- `egobb_process_plan_duration_seconds{provider_id}`
  - Timer for per-plan processing/upsert (or per-batch depending on implementation).

### Schedulers and recovery
- `egobb_scheduler_skips_total{scheduler}`
  - Counts skipped scheduler runs due to “already running” guard / non-overlap.
- `egobb_staging_requeued_total{provider_id}`
  - Counts rows requeued from `PROCESSING` back to `PENDING` after TTL (stuck recovery).

## Alert ideas (conceptual)

These are not mandatory to implement as actual rules, but they show operational intent.

### API alerts
- **High latency**: p95 `/search` over threshold for 10–15 minutes.
- **High error rate**: sustained 5xx > small percentage for 5–10 minutes.

### Pipeline alerts
- **Provider failing**: `rate(egobb_provider_snapshot_total{outcome="error"}[10m])` > 0 for 10 minutes.
- **Backlog growing**: ingestion rate > processing rate for 15–30 minutes, or requeue rate rising.
- **Scheduler stuck**: `egobb_scheduler_skips_total` increases continuously (suggests overlap / slow runs).

## Runbooks

### 1) Provider is failing (timeouts / 5xx / network)
**Symptoms**
- `egobb_provider_snapshot_total{outcome="error"}` increases.
- `egobb_provider_snapshot_duration_seconds` spikes.
- Worker-fetch logs show retries/backoff.

**What to do**
1. Confirm provider status from logs + metrics (error rate and duration).
2. Check your retry/backoff behavior: is it hammering or behaving politely?
3. Ensure the API remains healthy (it should, because it reads DB only).
4. If provider is down for a long time:
   - Accept “stale-but-correct” results from DB (historical data remains available).
   - Keep worker-fetch running with backoff; avoid aggressive polling.

**Success criteria**
- Errors stop; success counters resume; durations normalize.

---

### 2) Staging lag/backlog is growing (processing can’t keep up)
**Symptoms**
- Ingestion continues but processing rate drops.
- `/search` still works, but new events appear later than expected.
- `egobb_staging_requeued_total` might rise (stuck rows).

**What to do**
1. Scale `worker-process` horizontally:
   - `docker compose --scale worker-process=3 ...`
2. Inspect if workers are blocked by DB:
   - DB CPU/IO, connection pool saturation, slow queries.
3. Check for stuck rows recovery:
   - Confirm requeue scheduled job is running.
   - Rising `egobb_staging_requeued_total` suggests rows got stuck in `PROCESSING` and were recovered.

**Success criteria**
- Processing rate meets/exceeds ingestion rate over time.
- Requeue rate returns to near-zero.

---

### 3) API latency increases (DB pressure / slow queries)
**Symptoms**
- `http_server_requests_seconds` p95/p99 rises for `/search`.
- Error rate may remain low initially.

**What to do**
1. Correlate with DB metrics / logs.
2. Confirm your query plan is using the expected indexes.
3. Temporarily reduce load (if possible) and validate recovery.
4. If this is persistent: consider pagination, additional indexes, or query shape improvements.

**Success criteria**
- Latency returns to baseline without increasing 5xx.

## How to query quickly (examples)

Fetch metrics and filter:
```bash
curl -s http://localhost:18080/actuator/prometheus | grep -i egobb
```

Force `/search` access logs for testing:
```bash
SEARCH_SLOW_LOG_MS=0 LOG_FORMAT=json docker compose up -d --build api
```
