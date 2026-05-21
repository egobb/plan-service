# Demo: Backlog & Scale‑Out (DB‑Queue Staging + `SELECT … FOR UPDATE SKIP LOCKED`)

This demo proves that the ingestion pipeline is **horizontally scalable** without any external queue/broker:
- A **fetch worker** produces work by staging rows into Postgres.
- A **process worker** consumes staged rows in batches and upserts canonical data.
- We **intentionally create a backlog**, observe it growing, then **scale out** processing to catch up.
- Scale‑out works because processors **claim disjoint batches** using Postgres row‑locking with `SELECT … FOR UPDATE SKIP LOCKED`.

> **Short story:** We treat Postgres as a *durable queue* (“DB‑queue”). Staging makes ingestion resilient and observable, while `SKIP LOCKED` makes parallel processing safe and coordination‑free.

---

## Why this demo matters

In real systems, upstream providers are often bursty or unreliable. If you ingest snapshots (or large batches) you need:
- **Stable API latency** (API must not call upstream at runtime),
- **A buffer** (staging) to absorb spikes,
- **A scalable consumer** (processors) that can catch up by adding replicas,
- **Correctness guarantees** (no double‑processing, safe retries).

This demo showcases all of the above with a repeatable, measurable workflow.

---

## Architecture (mental model)

- **`ingestion_runs`**: per‑snapshot *run tracking* (audit + gating).  
  A run transitions `RUNNING → STAGED` (or `FAILED`). Processors **only** consume rows from `STAGED` runs to avoid partial snapshot contamination.

- **`staging_plans`**: a **DB‑queue** of work items.  
  Rows move `PENDING → PROCESSING → DONE/FAILED`, with `attempts`, `claimed_at`, and `last_error`.

- **`plans`**: the canonical model served by `GET /search` (query‑only, stable latency).

---

## Key scaling mechanism: `SKIP LOCKED`

Each processor replica repeatedly “claims” a batch:

1) **Claim** (transactional, short‑lived)
```sql
SELECT sp.id
FROM staging_plans sp
JOIN ingestion_runs ir ON ir.id = sp.run_id
WHERE sp.status = 'PENDING'
  AND sp.attempts < :maxAttempts
  AND ir.status = 'STAGED'
ORDER BY sp.created_at ASC
FOR UPDATE OF sp SKIP LOCKED
LIMIT :batch;
```

2) **Mark as processing**
```sql
UPDATE staging_plans
SET status = 'PROCESSING', claimed_at = now()
WHERE id IN (...claimed...);
```

3) **Process** outside the claim transaction and mark `DONE`/`FAILED`.

`SKIP LOCKED` guarantees that multiple replicas can claim **different rows** without waiting or stepping on each other—no distributed locks, no broker needed.

---

## Prerequisites

- Docker + Docker Compose
- The project can be started with the compose file at `deploy/docker-compose.yml`
- You can run `psql` inside the `db` container

---

## Demo script (copy/paste)

### 0) Clean start (recommended)
From the repo root:

```bash
docker compose -f deploy/docker-compose.yml down -v
```

---

### 1) Start the stack with parameters that create backlog

We will:
- **Speed up fetch**: short poll interval + large staging batch.
- **Slow down process**: longer poll interval + small processing batch.
- Start with **one** processor replica so backlog is clearly visible.

#### Option A — `make run-scaled`

```bash
WORKER_FETCH_POLL_INTERVAL_MS=2000 WORKER_FETCH_BATCH_SIZE=2000 WORKER_PROCESS_POLL_INTERVAL_MS=15000 WORKER_PROCESS_BATCH_SIZE=50 API=1 PROCESS=1 make run-scaled
```

#### Option B — `docker compose` directly

```bash
WORKER_FETCH_POLL_INTERVAL_MS=2000 WORKER_FETCH_BATCH_SIZE=2000 WORKER_PROCESS_POLL_INTERVAL_MS=15000 WORKER_PROCESS_BATCH_SIZE=50 docker compose -f deploy/docker-compose.yml up -d --build
```

Ensure a single processor replica:
```bash
docker compose -f deploy/docker-compose.yml up -d --scale worker-process=1
```

---

### 2) Observe backlog growth (SQL)

Run this 2–3 times (every ~5–10s):

```bash
docker compose -f deploy/docker-compose.yml exec -T db   psql -U egobb -d egobb -c   "select status, count(*) from staging_plans group by status order by status;"
```

**Expected (before scaling):**
- `PENDING` grows (or stays high),
- `DONE/PROCESSED` increases more slowly.

(Optional) check recent runs:
```bash
docker compose -f deploy/docker-compose.yml exec -T db   psql -U egobb -d egobb -c   "select started_at, status, staged_plans_count, processed_plans_count, failed_plans_count from ingestion_runs order by started_at desc limit 5;"
```

---

### 3) (Optional) Correlate with Prometheus metrics

```bash
curl -s http://localhost:18080/actuator/prometheus | egrep "egobb_(provider_snapshot_total|stage_snapshot_total|process_plan_total|staging_requeued_total)"
```

**What you’re looking for:**
- staging counters grow quickly (producer),
- processing counters lag behind (consumer).

---

### 4) Scale out processors (the punchline)

```bash
docker compose -f deploy/docker-compose.yml up -d --scale worker-process=3
```

---

### 5) Verify backlog drain (SQL)

Repeat the staging status query (every ~5–10s):

```bash
docker compose -f deploy/docker-compose.yml exec -T db   psql -U egobb -d egobb -c   "select status, count(*) from staging_plans group by status order by status;"
```

**Expected (after scaling):**
- `PENDING` starts decreasing (or stops growing),
- `DONE/PROCESSED` grows noticeably faster.

---

### 6) (Optional) Show parallelism in logs

```bash
docker compose -f deploy/docker-compose.yml logs -f worker-process
```

**Expected:**
- each replica claims and processes batches continuously,
- no “double processing” of the same staging row.

---

## Tuning knobs (if results are not dramatic)

### If backlog does not grow
- Increase fetch batch: `WORKER_FETCH_BATCH_SIZE=5000`
- Decrease fetch poll: `WORKER_FETCH_POLL_INTERVAL_MS=1000`
- Reduce process batch: `WORKER_PROCESS_BATCH_SIZE=20`

### If Postgres becomes the bottleneck (too aggressive)
- Lower fetch batch to 1000–2000
- Increase fetch poll to 3000–5000
- Keep process batch around 50–200

---

## Notes

- “`staging_plans` behaves like a durable DB‑queue; `ingestion_runs` gates processing so partial snapshots never leak.”
- “We scale processors horizontally with `FOR UPDATE SKIP LOCKED`: disjoint batch claiming, no coordination service.”
- “Backlog growth is a signal to scale `worker-process`; if DB becomes the limiter, we tune batch sizes and pool limits.”

---

## Clean up

```bash
docker compose -f deploy/docker-compose.yml down -v
```
