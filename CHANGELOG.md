# Changelog

All notable changes to this project will be documented in this file.

## [0.10.0] - 2026-01-19

### Added
- Local **load balancer** (Traefik) to enable scaling `api` replicas under Docker Compose without host port collisions.
- Documentation updates describing how local scaling works with an L7 proxy (Traefik binds `8080`, `api` replicas are internal).

### Changed
- Local scaling guidance: `make run-scaled` assumes the API is reached through the load balancer at `http://localhost:8080`.

## [0.9.0] - 2026-01-19

### Added
- **Bulkhead** (Resilience4j) for `/search` to cap **in-flight** requests and protect the DB connection pool. When full, the API fails fast with `503` and error code `TOO_BUSY`.
- New configuration knobs:
  - `SEARCH_MAX_CONCURRENT_CALLS` (default `8`)
  - `SEARCH_MAX_WAIT_DURATION` (default `0ms`)
- Resilience4j metrics exported via Micrometer/Actuator.

### Changed
- README updated with resilience guardrails and operational signals (bulkhead rejects and provider circuit state).
## [0.8.1] - 2026-01-19

### Fixed
- Prevented **partial snapshot consumption**: worker-process now claims staged rows **only** when the associated `ingestion_run` is in `STAGED`.
- On staging failures mid-stream, all rows for the run are now marked **FAILED** to avoid leaving orphan `PENDING/PROCESSING` rows.

### Changed
- `/search` now accepts both:
  - ISO **offset date-time** (e.g. `2026-01-01T00:00:00Z`, `2026-01-01T00:00:00+02:00`)
  - ISO **local date-time** without offset (e.g. `2026-01-01T00:00:00`), interpreted using `api.timezone` (default `UTC`).
- Response `start_date/start_time/end_date/end_time` are rendered using a **configurable timezone** via `api.timezone` (default `UTC`).

### Added
- `ApiProperties` (`api.timezone`) to configure “local time” rendering deterministically.
- Integration tests proving:
  - claim gating by run status (`RUNNING/FAILED` not claimed, `STAGED` claimed),
  - local date-time parsing behavior,
  - timezone-based rendering (e.g. `Europe/Madrid`).

### Notes
- Default timezone remains `UTC` for determinism; override with `API_TIMEZONE` if required.

## [0.8.0] - 2026-01-19

### Added
- **Prometheus-ready metrics** via Spring Boot Actuator (`/actuator/prometheus`) including HTTP server metrics for `/search`.
- **Custom domain metrics** for ingestion and processing (provider fetch, staging inserts, processing throughput/failures, scheduler skips, and re-queue activity).
- **Request correlation** for the API: accepts or generates `X-Request-Id`, returns it in responses, and propagates it through logs.
- **Selective access logging** for `/search` (logs only slow requests and errors to reduce noise).
- Optional **JSON structured logging** (toggle via environment) to make logs ingestion-friendly.

### Changed
- Docker Compose includes an optional Prometheus setup (profile-based) for local verification.
- README updated with an operational story (SLIs, alerting, and incident playbooks).

## [0.7.0] - 2026-01-18

### Added
- Added crash recovery by re-queuing stuck `PROCESSING` staged rows after a configurable TTL.

### Fixed
- Fixed real horizontal scaling for `worker-process` by removing the global advisory lock and relying on `SELECT ... FOR UPDATE SKIP LOCKED`.
- Fixed temporal correctness: `seenAt` is now taken from the snapshot/run timestamp (staging/run metadata) instead of `Instant.now()` during processing.
- Made Postgres upserts monotonic to prevent older snapshots from overwriting newer plan state (timestamps and sell_mode updates guarded by snapshot recency).

### Security
- Hardened XML parsing (StAX) by disabling DTD and external entities to reduce XXE/entity expansion risks.

### Changed
- Updated staging model (`StagedPlan`) to carry snapshot/run `seenAt` metadata and propagated it through processing and tests.
- Updated documentation and compose defaults to reflect the scaling and recovery behavior.


## [0.6.1] - 2026-01-18

### Fixed
- Fixed provider snapshot streaming tests to match the real provider XML shape (`planList/output/base_plan/plan/zone`) and the composite external id format (`base_plan_id:plan_id`).

## [0.6.0] - 2026-01-18

### Added
- Optional **multi-provider configuration** (`providers.items`) with a `ProviderRegistry` that instantiates one `ProviderAdapter` per provider.
- Fetch worker now loops all configured providers and uses a **per-provider lock key** (`{lockName}:{providerId}`) to avoid overlapping runs per provider.
- Process worker can now consume a **shared staging queue** across providers via `claimNextAnyProvider(...)`.
- MDC improvements: workers populate `app_mode`, `provider_id` and `run_id` consistently to improve log correlation.

### Changed
- `StageSnapshotService` now stages by `providerId` (the command carries providerId) instead of relying on a single injected provider.

### Notes
- Backwards-compatible: if `providers.items` is not set, the app falls back to `provider.*`.

## [0.5.0] - 2026-01-18

### Added
- Local scaling target: `make run-scaled` with `API=<k>` and `PROCESS=<n>` to scale `api` and `worker-process` under Docker Compose.
- DB guardrails in Docker Compose: explicit `DB_POOL_MAX`/`DB_POOL_MIN_IDLE` per service (API, worker-fetch, worker-process) to keep total connections under control when scaling.
- Short-lived in-memory cache for hot `/search` ranges (first page only) using Spring Cache + Caffeine:
  - cache: `searchHotRanges`
  - TTL: 3s
  - max entries: 2000

### Changed
- Provider id is now read from configuration (`provider.id`) end-to-end in staging + processing (no hardcoded `demo-provider`).
- Integration tests adjusted to avoid truncating `staging_plans` via `CASCADE` (which implicitly wipes `staging_zones`).

## [0.4.0] - 2026-01-18

### Added
- Real provider integration enabled by default (XML snapshot).
- Streaming XML parsing (StAX) approach to support **massive** provider snapshots without loading them fully in memory.
- **DB-queue staging** layer in Postgres for ingestion runs and staged entities, enabling resilient and scalable processing:
  - `ingestion_runs`
  - `staging_plans`
  - `staging_zones`
- Split workers for better scaling and clearer responsibilities:
  - `worker-fetch`: fetches provider snapshot and stages raw data
  - `worker-process`: processes staged data into canonical `plans`/`zones`
- Adminer service in `docker-compose` for easy Postgres inspection during development.
- Retry/backoff configuration for provider fetch (attempts, backoff, max backoff, jitter, optional retry on parse errors).

### Changed
- Provider adapter now supports provider responses served as `text/xml` (in addition to `application/xml`).
- Ingestion pipeline is now **two-phase** (stage → process) to decouple provider variability from API latency.
- `plans` upsert semantics aligned to the domain model:
  - `ever_online` is **sticky** (once true, stays true)
  - `last_sell_mode` persisted
  - `first_seen_at` set on insert, `last_seen_at` updated on every upsert batch using `seenAt`
- Operational defaults updated for local execution via `make run` / Docker Compose.

### Fixed
- Scheduler execution issues when running in the wrong `app.mode` by clarifying modes and default compose wiring.
- Provider fetch failures due to missing HttpMessageConverter for `InputStream` when content type is `text/xml`.
- Reduced fragility in tests around fluent `RestClient` chaining and varargs `accept(...)` verification.

### Notes
- The upstream provider can occasionally return transient 5xx/503 responses. Retries/backoff are in place; local runs may occasionally show warnings due to provider availability.

## [0.3.0] - 2026-01-17

### Added
- Postgres persistence for `plans` and `zones` with upsert behavior and historical visibility.
- Scheduled polling worker to ingest provider snapshots periodically.
- API endpoint `GET /search` reading exclusively from the database to keep runtime latency stable.

### Fixed
- SQL parameter typing issues when filtering with nullable timestamps by switching to dynamic WHERE building / casting.
- Test setup and local run improvements (Makefile targets, Docker packaging).

## [0.2.0] - 2026-01-16

### Added
- Initial API contract and OpenAPI-aligned endpoint scaffolding.
- Basic domain model for plans and zones.

## [0.1.0] - 2026-01-15

### Added
- Project bootstrap: Spring Boot baseline, packaging, and initial repository structure.
