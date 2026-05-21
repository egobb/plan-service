
### Search Flow

This sequence captures the request path for /search. The controller validates input, delegates to the query handler, and retrieves results via the read repository from Postgres. The key property is that the request path is fully deterministic and provider-independent: the external provider is never contacted during /search, so response times depend only on the database.

```mermaid
sequenceDiagram
  autonumber
  participant U as Client
  participant SC as SearchController
  participant QH as PlanBetweenDatesQueryHandler
  participant PR as PlanReadRepository (PlanPostgresAdapter)
  participant DB as Postgres

  U->>SC: GET /search?starts_at&ends_at&limit&offset
  SC->>QH: build+handle query
  QH->>PR: search(startsAt, endsAt, limit, offset)
  PR->>DB: SELECT ... FROM plans WHERE ever_online=true ...
  DB-->>PR: rows
  PR-->>QH: List<PlanView>
  QH-->>SC: List<PlanView>
  SC-->>U: ApiResponse{data.events, error=null}
```

### Staging Flow

This sequence describes how snapshot ingestion starts. The scheduled job acquires a distributed advisory lock (per provider) to prevent overlapping runs, then streams the XML snapshot and stages plans in batches into Postgres. Staging provides auditability and decouples network/parse time from processing time, enabling safe retries and controlled throughput.

```mermaid
sequenceDiagram
  autonumber
  participant S as StageSnapshotScheduled
  participant L as AdvisoryLockService
  participant PL as PostgresAdvisoryLockService
  participant H as StageSnapshotCmdHandler
  participant D as StageSnapshotService
  participant R as IngestionRunPostgresRepository
  participant REG as ProviderRegistry
  participant PA as ProviderAdapter
  participant P as Provider API
  participant X as ProviderXmlStreamParser (StAX)
  participant ST as StagingPlanPostgresRepository
  participant DB as Postgres

  S->>L: tryWithLock(lockName:base+providerId)
  L->>PL: pg_try_advisory_lock(hash(lockName))
  alt lock acquired
    PL-->>L: handle(open JDBC connection)
    S->>H: handle(StageSnapshotCmd(providerId,batchSize))
    H->>D: stage(providerId,batchSize)
    D->>R: createRun(providerId, now)
    R->>DB: INSERT ingestion_runs
    D->>REG: getRequired(providerId)
    REG-->>D: ProviderPort
    D->>PA: streamPlans(consumer)
    PA->>P: GET snapshot (stream)
    P-->>PA: InputStream
    PA->>X: parse(InputStream)
    loop every N plans
      X-->>D: ProviderPlan callback
      D->>ST: insertPlanBatch(runId, providerId, batch)
      ST->>DB: INSERT staging_plans (batch)
    end
    D->>ST: countForRun(runId)
    ST->>DB: SELECT COUNT(*)
    D->>R: markStaged(runId, finishedAt, stagedCount)
    R->>DB: UPDATE ingestion_runs
    H-->>S: StageResult(runId, stagedCount)
    L-->>PL: close() => pg_advisory_unlock + close conn
  else lock not acquired
    PL-->>L: empty
    S-->>S: skip providerId
  end
```

### Staging Flow

This sequence shows how staged rows are processed concurrently. Workers atomically claim batches using SELECT … FOR UPDATE SKIP LOCKED, transform each row into the canonical model, and upsert into the plans table. Failed rows are retried up to a configured limit, ensuring at-least-once processing while keeping the system resilient to transient DB/network issues.

```mermaid
sequenceDiagram
  autonumber
  participant S as ProcessStagedPlansScheduled
  participant H as ProcessStagedPlansCmdHandler
  participant D as ProcessStagedPlansService
  participant ST as StagingPlanPostgresRepository
  participant WR as PlanWriteRepository (PlanPostgresAdapter)
  participant DB as Postgres

  S->>H: handle(ProcessStagedPlansCmd(batch,maxAttempts))
  H->>D: processBatch(batch,maxAttempts)
  D->>ST: claimNextAnyProvider(batch,maxAttempts)
  ST->>DB: SELECT id FROM staging_plans WHERE status=PENDING ... FOR UPDATE SKIP LOCKED LIMIT batch
  ST->>DB: UPDATE staging_plans SET status=PROCESSING, claimed_at=now WHERE id IN (...)
  ST->>DB: SELECT sp.* + run_started_at JOIN ingestion_runs WHERE id IN (...)
  DB-->>ST: List<StagedPlan>
  loop each staged plan
    D->>WR: upsertAll([PlanUpsert], seenAt=run_started_at)
    WR->>DB: INSERT ... ON CONFLICT DO UPDATE (monotonic)
    alt success
      D->>ST: markDone(id)
      ST->>DB: UPDATE staging_plans SET status=DONE, processed_at=now
    else failure
      D->>ST: markFailed(id, error, maxAttempts)
      ST->>DB: UPDATE attempts/status/(PENDING|FAILED)
    end
  end
```