-- v0.4 schema

CREATE TABLE IF NOT EXISTS plans (
  id UUID PRIMARY KEY,
  provider_id TEXT NOT NULL,
  external_plan_id TEXT NOT NULL,
  title TEXT NOT NULL,
  starts_at TIMESTAMPTZ NOT NULL,
  ends_at TIMESTAMPTZ NULL,
  min_price NUMERIC NULL,
  max_price NUMERIC NULL,
  ever_online BOOLEAN NOT NULL,
  last_sell_mode TEXT NOT NULL,
  first_seen_at TIMESTAMPTZ NOT NULL,
  last_seen_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_plans_provider_external ON plans(provider_id, external_plan_id);
CREATE INDEX IF NOT EXISTS ix_plans_starts_at ON plans(starts_at);
CREATE INDEX IF NOT EXISTS ix_plans_ever_online ON plans(ever_online);

-- TODO: ever_online, starts_at, ends_at
-- ever_online, ends_at

-- Ingestion runs (audit + troubleshooting)
CREATE TABLE IF NOT EXISTS ingestion_runs (
  id UUID PRIMARY KEY,
  provider_id TEXT NOT NULL,
  started_at TIMESTAMPTZ NOT NULL,
  finished_at TIMESTAMPTZ NULL,
  status TEXT NOT NULL,
  staged_plans_count INT NOT NULL DEFAULT 0,
  processed_plans_count INT NOT NULL DEFAULT 0,
  failed_plans_count INT NOT NULL DEFAULT 0,
  last_error TEXT NULL
);

CREATE INDEX IF NOT EXISTS ix_ingestion_runs_provider_started_at ON ingestion_runs(provider_id, started_at DESC);

-- Staging queue for plans
CREATE TABLE IF NOT EXISTS staging_plans (
  id UUID PRIMARY KEY,
  run_id UUID NOT NULL REFERENCES ingestion_runs(id) ON DELETE CASCADE,
  provider_id TEXT NOT NULL,
  external_plan_id TEXT NOT NULL,
  title TEXT NOT NULL,
  sell_mode TEXT NULL,
  starts_at TIMESTAMPTZ NOT NULL,
  ends_at TIMESTAMPTZ NULL,
  min_price NUMERIC NULL,
  max_price NUMERIC NULL,
  created_at TIMESTAMPTZ NOT NULL,
  status TEXT NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  claimed_at TIMESTAMPTZ NULL,
  processed_at TIMESTAMPTZ NULL,
  last_error TEXT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_staging_plans_run_external ON staging_plans(run_id, external_plan_id);
CREATE INDEX IF NOT EXISTS ix_staging_plans_pick ON staging_plans(provider_id, status, attempts, created_at);
