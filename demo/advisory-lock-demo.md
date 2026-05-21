# Demo runbook: Advisory Lock (1 API, 1 worker-process, 5 worker-fetch)

This demo shows how the **Postgres advisory lock** prevents multiple **fetch workers** from running the same provider fetch concurrently across replicas.

Goal topology:
- `api`: **1**
- `worker-process`: **1**
- `worker-fetch`: **5**
- `postgres`: **1**

> Notes
> - Commands assume `docker compose -f deploy/docker-compose.yml ...`
> - If your project uses different ports/paths, adjust accordingly.
> - The key signal is: **only 1 fetcher performs the critical section**, others **skip** because they can’t acquire the lock.

---

## 0) Pre-flight

### Check Docker Compose is available
```bash
docker compose version
```

### Optional: confirm expected services exist
```bash
docker compose -f deploy/docker-compose.yml config --services
```

---

## 1) Clean start

### Tear down everything (including volumes)
```bash
docker compose -f deploy/docker-compose.yml down -v
```

### Bring up with the target scaling
```bash
docker compose -f deploy/docker-compose.yml up -d --build   --scale api=1   --scale worker-process=1   --scale worker-fetch=5
```

### Confirm the topology
```bash
docker compose -f deploy/docker-compose.yml ps
```

You should see:
- 1x `api`
- 1x `worker-process`
- 5x `worker-fetch`
- 1x `postgres`

---

## 2) Verify the API (serves only from Postgres)

### Call the search endpoint
```bash
curl -s "http://localhost:18080/search?starts_at=2021-01-01T00:00:00Z&ends_at=2030-12-31T23:59:59Z" | jq
```

If you don’t have `jq`:
```bash
curl -s "http://localhost:18080/search?starts_at=2021-01-01T00:00:00Z&ends_at=2030-12-31T23:59:59Z"
```

---

## 3) Show the advisory lock behavior (logs)

### Tail logs of all fetchers
```bash
docker compose -f deploy/docker-compose.yml logs -f worker-fetch
```

What you want to highlight:
- **One** fetch worker acquires the lock and runs the staging critical section.
- The other **four** fetch workers report something equivalent to:
  - “lock not acquired; skipping”
  - or “skipped because another replica is running”
  - often tagged as `lock_not_acquired`.

> Tip (optional): if the poll interval is long, wait for the next scheduled tick.  
> If your env supports it, reduce the polling interval and restart `worker-fetch` to make the effect visible faster.

### (Optional) Restart fetchers to force contention right now
```bash
docker compose -f deploy/docker-compose.yml restart worker-fetch
docker compose -f deploy/docker-compose.yml logs -f worker-fetch
```

---

## 4) Show the advisory lock signal in Prometheus metrics

### Fetch Prometheus scrape output
```bash
curl -s http://localhost:18080/actuator/prometheus | head
```

### Filter for scheduler skips (lock contention)
```bash
curl -s http://localhost:18080/actuator/prometheus | grep egobb_scheduler_skips_total
```

You should see a counter with something like:
- `reason="lock_not_acquired"` increasing while multiple fetchers compete.

### Broader filter (in case metric names differ)
```bash
curl -s http://localhost:18080/actuator/prometheus | grep -E "scheduler|lock|advisory|fetch"
```

---

## 5) Show advisory locks in Postgres (optional but great for the interview)

### Open a psql session
```bash
docker compose -f deploy/docker-compose.yml exec db psql -U egobb -d egobb
```

### List advisory locks (during a fetch run)
```sql
SELECT
  a.pid,
  a.usename,
  a.application_name,
  a.client_addr,
  l.locktype,
  l.mode,
  l.granted
FROM pg_locks l
JOIN pg_stat_activity a ON a.pid = l.pid
WHERE l.locktype = 'advisory'
ORDER BY a.pid;
```

> Note: advisory locks are session-scoped. You’ll only see them while the lock-holding connection is alive.

---

## 6) Verify staging/canonical tables

Inside `psql`:
```sql
-- Recent ingestion runs (if you have an ingestion_runs table)
SELECT * FROM ingestion_runs ORDER BY started_at DESC LIMIT 10;

-- Staging backlog
SELECT COUNT(*) FROM staging_plans;

-- Canonical plans count
SELECT COUNT(*) FROM plans;
```

---

## 7) Why

- With **5 fetchers**, there are multiple replicas capable of running the scheduled fetch at the same time.
- The **advisory lock** ensures **only one** fetcher executes the critical section for a given provider.
- Others **skip immediately**, avoiding:
  - duplicate staging writes,
  - avoidable provider load,
  - confusing operational signals.

---

## 8) Tear down

### Stop (keep volumes)
```bash
docker compose -f deploy/docker-compose.yml down
```

### Full cleanup (remove volumes)
```bash
docker compose -f deploy/docker-compose.yml down -v
```
