# ADR 0001 - Snapshot ingestion via Postgres DB-queue staging

## Status
Accepted

## Context
The external provider exposes a single endpoint that returns the **current** set of plans as an **XML snapshot**. Plans that disappear from the snapshot must still be retrievable via `GET /search` if they were **ever** available online.

The service must keep `/search` fast (**hundreds of ms**) regardless of provider state (timeouts, high latency, downtime). That rules out calling the provider at request time.

In addition, the provider snapshot can be large and potentially expensive to parse. We need a design that:

- Decouples network and XML parsing from the user-facing API.
- Enables controlled retries and crash recovery.
- Allows horizontal scaling of processing without introducing extra infrastructure.
- Preserves an audit trail of what happened during ingestion.

## Decision
We ingest provider snapshots using a **two-stage pipeline** backed by Postgres:

1. **Fetch & stage** (`APP_MODE=worker-fetch`)
   - Periodically downloads the snapshot and parses it using a **streaming StAX parser**.
   - Writes results into `staging_plans` in batches.
   - Records run metadata in `ingestion_runs` (start/end, counts, status, last error).

2. **Claim & process** (`APP_MODE=worker-process`)
   - Processes staged rows in batches using a DB-queue pattern: `SELECT … FOR UPDATE SKIP LOCKED`.
   - Upserts into the canonical `plans` table.
   - Marks staged rows as `DONE` or requeues them with an incremented `attempts` counter.
   - Periodically requeues rows stuck in `PROCESSING` based on `claimed_at` TTL (crash recovery).

The API (`APP_MODE=api`) **only reads** from `plans` and never calls the provider.

## Consequences
### Positive
- **Stable API latency**: `/search` does not depend on provider availability.
- **Auditability**: `ingestion_runs` and `staging_plans` provide an operational history and debugging data.
- **Scalable processing**: `worker-process` can be scaled horizontally with no distributed lock thanks to `SKIP LOCKED`.
- **Crash recovery**: unprocessed work remains in Postgres and can be resumed by any worker; stuck rows can be requeued.
- **Infra-light**: no Kafka/queue required for a snapshot-driven input.

### Negative / trade-offs
- **DB pressure**: staging + processing increases write volume and may require careful indexing and connection pool sizing.
- **Throughput ceiling**: at very high ingestion rates, Postgres becomes the bottleneck compared to purpose-built queues.

### Mitigations
- Batch inserts to `staging_plans`.
- Conservative connection pools (DB guardrails) and controlled worker batch sizes.
- Targeted indexes for:
  - `/search` (`ever_online`, `starts_at`, optionally composite indexes)
  - queue claims (`status`, `attempts`, `created_at`)

## Alternatives considered
1. **Call provider directly from `/search`**
   - Rejected: violates latency and availability requirements.

2. **Ingest snapshot and write directly to `plans` (no staging)**
   - Rejected: harder crash recovery and retry semantics; parsing/network failures couple tightly to writes.

3. **Kafka (or external queue) for ingestion**
   - Considered viable for event-driven sources or extremely high throughput.
   - Rejected: provider is snapshot-based; DB-queue is simpler and provides built-in auditability.

## Notes
If the provider were to switch from snapshots to an **event stream**, Kafka (or similar) would likely become the preferred ingestion backbone.
