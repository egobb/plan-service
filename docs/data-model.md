
### Data Model

This ERD documents the persistence model and how ingestion is tracked end-to-end. plans stores the canonical, queryable state used by the API, while ingestion_runs and staging_plans act as an auditable “DB queue” for snapshot processing. Separating staging from canonical storage enables scalable processing, operational visibility, and safe recovery after partial failures.

```mermaid
erDiagram
  ingestion_runs ||--o{ staging_plans : contains
  plans {
    UUID id PK
    TEXT provider_id
    TEXT external_plan_id
    TEXT title
    TIMESTAMPTZ starts_at
    TIMESTAMPTZ ends_at
    NUMERIC min_price
    NUMERIC max_price
    BOOLEAN ever_online
    TEXT last_sell_mode
    TIMESTAMPTZ first_seen_at
    TIMESTAMPTZ last_seen_at
  }
  ingestion_runs {
    UUID id PK
    TEXT provider_id
    TIMESTAMPTZ started_at
    TIMESTAMPTZ finished_at
    TEXT status
    INT staged_plans_count
    INT processed_plans_count
    INT failed_plans_count
    TEXT last_error
  }
  staging_plans {
    UUID id PK
    UUID run_id FK
    TEXT provider_id
    TEXT external_plan_id
    TEXT title
    TEXT sell_mode
    TIMESTAMPTZ starts_at
    TIMESTAMPTZ ends_at
    NUMERIC min_price
    NUMERIC max_price
    TIMESTAMPTZ created_at
    TEXT status
    INT attempts
    TIMESTAMPTZ claimed_at
    TIMESTAMPTZ processed_at
    TEXT last_error
  }

```

> Known gap: `ingestion_runs.processed_plans_count` and `ingestion_runs.failed_plans_count`
> exist in the schema but are not currently updated by the process worker. They remain
> reserved fields for future run-level processing counters.
