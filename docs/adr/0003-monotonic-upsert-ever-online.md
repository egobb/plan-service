# ADR 0003 - Monotonic upserts and sticky `ever_online`

## Status
Accepted

## Context
The provider returns **only current** plans. The API must return any plan that was **ever** available with `sell_mode="online"`, even if it later disappears or becomes offline.

Additionally, ingestion is a pipeline where:

- Fetch can stage a large snapshot.
- Processing can be retried per row.
- Multiple process workers can run concurrently.

Therefore, writes into the canonical `plans` table must be:

- **Idempotent** (safe under retries and at-least-once processing).
- **Order-tolerant** (late retries must not regress canonical state).

## Decision
Use **upserts** with a unique key `(provider_id, external_plan_id)` and enforce the following semantics:

1. **Sticky `ever_online`**
   - When a staged plan has `sell_mode=ONLINE`, the canonical row sets `ever_online=true`.
   - Once `ever_online` becomes true, it never flips back to false.

2. **Track latest sell mode**
   - Persist `last_sell_mode` from the latest processed snapshot row.

3. **Monotonic timestamps**
   - Use `seenAt` derived from the ingestion run (`ingestion_runs.started_at`) for deterministic time semantics.
   - `first_seen_at` is set only on insert.
   - `last_seen_at` is updated on every upsert.

4. **API query semantics**
   - `/search` filters by `ever_online = true` and the provided time window.

## Consequences
### Positive
- **Correctness under retries**: reprocessing the same staged row does not create duplicates.
- **Concurrency-safe**: multiple workers can upsert without coordination beyond the DB unique key.
- **Historical behavior**: plans that were ever online remain queryable.

### Negative / trade-offs
- `ever_online` can only grow to true; it cannot express “was online, then revoked” as a terminal state.
- If snapshots can arrive out-of-order across runs, `last_sell_mode` may reflect the last processed row, not necessarily the provider’s latest truth.

### Mitigations
- Use ingestion run timestamps to reason about recency.
- If stronger “latest snapshot wins” is required, add a guard: only update `last_sell_mode` when `seenAt >= last_seen_at`.

## Alternatives considered
1. **Keep all snapshots as immutable history**
   - Considered: enables full temporal queries.
   - Rejected: out of scope and storage cost is not justified for current requirements.

2. **Soft delete / TTL for old plans**
   - Rejected: violates “past plans should be retrievable” requirement.
