
### Staging state machine

This state machine explains the lifecycle of a staged plan row. Rows move from PENDING to PROCESSING when claimed, then to DONE on success or back to PENDING/FAILED on failure depending on the retry budget. The model makes crash recovery explicit: unprocessed work remains in the database and can be picked up by any worker instance.

```mermaid
stateDiagram-v2
  [*] --> PENDING
  PENDING --> PROCESSING: claim (SKIP LOCKED)\nclaimed_at set
  PROCESSING --> DONE: upsert success
  PROCESSING --> PENDING: upsert fails\nattempts++\n(if attempts < max)
  PROCESSING --> FAILED: upsert fails\nattempts++\n(if attempts >= max)
  PROCESSING --> PENDING: requeue stuck\nclaimed_at older than TTL
  DONE --> [*]
  FAILED --> [*]
```