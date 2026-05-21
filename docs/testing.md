# Testing

This document describes the test strategy, what is covered by unit vs integration tests, and how to run them.

## Test pyramid

### Unit tests (fast, isolated)
Unit tests target pure logic and adapter behavior with mocks:

- Domain invariants and value objects (e.g., `PlanTest`).
- Query handler behavior and caching boundaries (e.g., `PlanBetweenDatesQueryHandlerTest`).
- Services orchestrating the pipeline (e.g., `StageSnapshotServiceTest`, `ProcessStagedPlansServiceTest`).
- Infrastructure components with mocks (e.g., `ProviderAdapterTest`, scheduled jobs tests).

### Integration tests (real Postgres)
Integration tests validate real SQL behavior and end-to-end flows with **Testcontainers** Postgres:

- Repository correctness (e.g., `PlanPostgresAdapterIT`).
- Controller contract + wiring (e.g., `SearchControllerIT`, `SearchControllerErrorIT`).
- Staging → processing → canonical upsert flow (e.g., `StagingProcessingIT`).
- Provider streaming against a local stub/fixture where applicable.

## Running tests

### All tests (unit + integration)

```bash
make test
```

Under the hood, this runs Maven `verify`, which executes:

- **Surefire** for unit tests (`*Test`)
- **Failsafe** for integration tests (`*IT`)

### Run only unit tests

```bash
./mvnw -f app/pom.xml -B test
```

### Run only integration tests

```bash
./mvnw -f app/pom.xml -B -DskipTests=false -Dtest="!*" -DfailIfNoTests=false verify
```

> Tip: integration tests require Docker (Testcontainers).

## What the tests validate

### Functional requirements
- `/search` returns only plans that were **ever** online (`ever_online=true`).
- Plans remain queryable even if they disappear from the provider snapshot (historical behavior).
- Time-window filtering and parameter validation follows the API contract.

### Non-functional behavior
- `/search` does not call the provider (API reads only from Postgres).
- Staging and processing are idempotent under retries.
- `worker-process` can be scaled (claim semantics are DB-atomic via `FOR UPDATE SKIP LOCKED`).
- Crash recovery path: stuck `PROCESSING` rows can be requeued.

## Suggested extensions (optional)
If you want to expand coverage further:

- **Concurrency test**: spawn multiple process workers and assert no duplicate processing under contention.
- **Index/plan regression test**: capture `EXPLAIN (ANALYZE, BUFFERS)` for the `/search` query on a large dataset.
- **Provider failure matrix**: explicit tests for timeouts, 5xx, 429 retry, and malformed XML.
