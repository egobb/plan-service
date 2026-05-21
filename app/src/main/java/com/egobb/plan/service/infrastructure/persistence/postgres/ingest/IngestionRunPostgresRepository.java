package com.egobb.plan.service.infrastructure.persistence.postgres.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class IngestionRunPostgresRepository {

	private static final int MAX_ERROR_LEN = 2000;

	private final JdbcTemplate jdbcTemplate;

	public IngestionRunPostgresRepository(final JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public UUID createRun(final String providerId, final Instant now) {
		final UUID runId = UUID.randomUUID();

		this.jdbcTemplate.update("""
				INSERT INTO ingestion_runs (id, provider_id, started_at, status)
				VALUES (?, ?, ?, ?)
				""", runId, providerId, Timestamp.from(now), IngestionRunStatus.RUNNING.name());

		return runId;
	}

	public void markStaged(final UUID runId, final Instant now, final int stagedPlansCount) {
		this.jdbcTemplate.update("""
				UPDATE ingestion_runs
				SET finished_at = ?, status = ?, staged_plans_count = ?
				WHERE id = ?
				""", Timestamp.from(now), IngestionRunStatus.STAGED.name(), stagedPlansCount, runId);
	}

	public void markFailed(final UUID runId, final Instant now, final String error) {
		this.jdbcTemplate.update("""
				UPDATE ingestion_runs
				SET finished_at = ?, status = ?, last_error = ?
				WHERE id = ?
				""", Timestamp.from(now), IngestionRunStatus.FAILED.name(), truncate(error, MAX_ERROR_LEN), runId);
	}

	private static String truncate(final String s, final int maxLen) {
		if (s == null)
			return null;
		if (s.length() <= maxLen)
			return s;
		return s.substring(0, maxLen);
	}
}
