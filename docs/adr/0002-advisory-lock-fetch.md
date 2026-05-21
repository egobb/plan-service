# ADR 0002 - Per-provider Postgres advisory lock for snapshot fetch

## Status
Accepted

## Context
The fetch worker (`APP_MODE=worker-fetch`) is scheduled periodically. In real deployments, multiple instances of the fetch worker may run (by mistake, during deployments, or for HA). Without coordination, overlapping runs for the same provider can cause:

- Duplicate staging writes and unnecessary DB load.
- Increased provider pressure (thundering herd).
- Confusing operational signals (multiple runs competing).

We need a lightweight distributed guard that prevents **overlapping fetch runs per provider**.

## Decision
Use a **Postgres advisory lock** (`pg_try_advisory_lock`) keyed by a deterministic lock name derived from `provider_id`.

- The scheduled job attempts to acquire the lock before starting a fetch.
- If the lock is not acquired, the run is skipped (another instance is doing it).
- The lock is tied to a DB session/connection, so the lock service holds a dedicated connection for the duration of the run.
- The feature can be disabled via configuration (e.g., `WORKER_LOCK_ENABLED=false`) when single-instance fetch is guaranteed.

## Consequences
### Positive
- **No overlapping runs** per provider across instances.
- **No extra infrastructure** required.
- **Crash safety**: if the process dies, the connection closes and Postgres releases the advisory lock.

### Negative / trade-offs
- Requires holding a DB connection for the duration of the fetch run.
- Advisory locks are Postgres-specific (reduced portability).

### Mitigations
- Keep the fetch DB pool small.
- Use streaming parsing and batch staging to minimize fetch duration.

## Alternatives considered
1. **In-memory guard (`AtomicBoolean`) only**
   - Rejected: only prevents overlap within a single JVM.

2. **DB row lock in a lock table**
   - Considered: portable but requires lock rows, cleanup, and can be more error-prone.

3. **External distributed lock (Redis, Zookeeper, etc.)**
   - Rejected: additional infrastructure and operational complexity.
