package com.egobb.plan.service.infrastructure.persistence.postgres.ingest;

import com.egobb.plan.service.application.ingest.ProviderPlan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class StagingPlanPostgresRepository {

	private static final RowMapper<StagedPlan> ROW_MAPPER = (rs, rowNum) -> {
		final Timestamp startsTs = rs.getTimestamp("starts_at");
		final Timestamp endsTs = rs.getTimestamp("ends_at");
		final Timestamp runStartedTs = rs.getTimestamp("run_started_at");

		return new StagedPlan(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("run_id")),
				rs.getString("provider_id"), rs.getString("external_plan_id"), rs.getString("title"),
				rs.getString("sell_mode"), startsTs == null ? null : startsTs.toInstant(),
				endsTs == null ? null : endsTs.toInstant(), rs.getBigDecimal("min_price"),
				rs.getBigDecimal("max_price"), runStartedTs == null ? null : runStartedTs.toInstant(),
				rs.getInt("attempts"));
	};

	private final JdbcTemplate jdbcTemplate;
	private final TransactionTemplate txTemplate;

	public StagingPlanPostgresRepository(final JdbcTemplate jdbcTemplate, final PlatformTransactionManager txManager) {
		this.jdbcTemplate = jdbcTemplate;
		this.txTemplate = new TransactionTemplate(txManager);
	}

	public void insertPlanBatch(final UUID runId, final String providerId, final List<ProviderPlan> plans,
			final Instant now) {
		if (plans == null || plans.isEmpty()) {
			return;
		}

		final Timestamp createdAt = toTs(now);

		this.jdbcTemplate.batchUpdate("""
				INSERT INTO staging_plans (
				  id, run_id, provider_id, external_plan_id, title, sell_mode,
				  starts_at, ends_at, min_price, max_price,
				  created_at, status, attempts
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (run_id, external_plan_id) DO UPDATE SET
				  title = EXCLUDED.title,
				  sell_mode = EXCLUDED.sell_mode,
				  starts_at = EXCLUDED.starts_at,
				  ends_at = EXCLUDED.ends_at,
				  min_price = EXCLUDED.min_price,
				  max_price = EXCLUDED.max_price
				""", plans, plans.size(), (ps, plan) -> bindInsert(ps, runId, providerId, plan, createdAt));
	}

	private static void bindInsert(final PreparedStatement ps, final UUID runId, final String providerId,
			final ProviderPlan plan, final Timestamp createdAt) throws SQLException {

		ps.setObject(1, UUID.randomUUID());
		ps.setObject(2, runId);
		ps.setString(3, providerId);
		ps.setString(4, plan.externalPlanId());
		ps.setString(5, plan.title());
		ps.setString(6, plan.sellMode() == null ? null : plan.sellMode().value());

		// IMPORTANT: use Timestamp for Instants
		ps.setTimestamp(7, toTs(plan.startsAt()));
		ps.setTimestamp(8, toTs(plan.endsAt()));

		ps.setBigDecimal(9, plan.minPrice());
		ps.setBigDecimal(10, plan.maxPrice());

		ps.setTimestamp(11, createdAt);
		ps.setString(12, StagingPlanStatus.PENDING.name());
		ps.setInt(13, 0);
	}

	public int countForRun(final UUID runId) {
		final Integer count = this.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM staging_plans WHERE run_id = ?",
				Integer.class, runId);
		return count == null ? 0 : count;
	}

	public List<StagedPlan> claimNext(final String providerId, final int limit, final int maxAttempts) {
		return this.txTemplate.execute(status -> {

			// IMPORTANT:
			// Only process rows belonging to a fully staged run
			// (ingestion_runs.status=STAGED).
			// This prevents worker-process from consuming a partial snapshot if
			// worker-fetch fails mid-stream.
			final List<UUID> ids = this.jdbcTemplate.query("""
					SELECT sp.id
					FROM staging_plans sp
					JOIN ingestion_runs ir ON ir.id = sp.run_id
					WHERE sp.provider_id = ?
					  AND sp.status = 'PENDING'
					  AND sp.attempts < ?
					  AND ir.status = 'STAGED'
					ORDER BY sp.created_at ASC
					FOR UPDATE OF sp SKIP LOCKED
					LIMIT ?
					""", (rs, rowNum) -> UUID.fromString(rs.getString("id")), providerId, maxAttempts, limit);

			if (ids.isEmpty()) {
				return List.of();
			}

			final String inClause = String.join(",", ids.stream().map(x -> "?").toList());

			final List<Object> params = new ArrayList<>();
			params.add(toTs(Instant.now())); // claimed_at
			params.add(StagingPlanStatus.PROCESSING.name());
			params.addAll(ids);

			this.jdbcTemplate.update(
					"UPDATE staging_plans SET claimed_at = ?, status = ? WHERE id IN (" + inClause + ")",
					params.toArray());

			final String inClause2 = String.join(",", ids.stream().map(x -> "?").toList());
			final List<Object> params2 = new ArrayList<>(ids);

			return this.jdbcTemplate.query("""
					SELECT sp.*, ir.started_at AS run_started_at
					FROM staging_plans sp
					JOIN ingestion_runs ir ON ir.id = sp.run_id
					WHERE sp.id IN (""" + inClause2 + ")", ROW_MAPPER, params2.toArray());
		});
	}

	/**
	 * Claims the next pending staged plans regardless of provider.
	 * <p>
	 * This allows worker-process nodes to process a shared queue when multiple
	 * providers are configured.
	 */
	public List<StagedPlan> claimNextAnyProvider(final int limit, final int maxAttempts) {
		return this.txTemplate.execute(status -> {

			// IMPORTANT:
			// Only process rows belonging to a fully staged run
			// (ingestion_runs.status=STAGED).
			// This prevents worker-process from consuming a partial snapshot if
			// worker-fetch fails mid-stream.
			final List<UUID> ids = this.jdbcTemplate.query("""
					SELECT sp.id
					FROM staging_plans sp
					JOIN ingestion_runs ir ON ir.id = sp.run_id
					WHERE sp.status = 'PENDING'
					  AND sp.attempts < ?
					  AND ir.status = 'STAGED'
					ORDER BY sp.created_at ASC
					FOR UPDATE OF sp SKIP LOCKED
					LIMIT ?
					""", (rs, rowNum) -> UUID.fromString(rs.getString("id")), maxAttempts, limit);

			if (ids.isEmpty()) {
				return List.of();
			}

			final String inClause = String.join(",", ids.stream().map(x -> "?").toList());

			final List<Object> params = new ArrayList<>();
			params.add(toTs(Instant.now())); // claimed_at
			params.add(StagingPlanStatus.PROCESSING.name());
			params.addAll(ids);

			this.jdbcTemplate.update(
					"UPDATE staging_plans SET claimed_at = ?, status = ? WHERE id IN (" + inClause + ")",
					params.toArray());

			final String inClause2 = String.join(",", ids.stream().map(x -> "?").toList());
			final List<Object> params2 = new ArrayList<>(ids);

			return this.jdbcTemplate.query("""
					SELECT sp.*, ir.started_at AS run_started_at
					FROM staging_plans sp
					JOIN ingestion_runs ir ON ir.id = sp.run_id
					WHERE sp.id IN (""" + inClause2 + ")", ROW_MAPPER, params2.toArray());
		});
	}

	/**
	 * Requeues stuck rows that were claimed (status=PROCESSING) but never
	 * completed, typically due to a crash/kill after claiming.
	 */
	public int requeueStuckProcessing(final Instant claimedBefore) {
		if (claimedBefore == null) {
			return 0;
		}
		return this.jdbcTemplate.update("""
				UPDATE staging_plans
				SET status = ?, claimed_at = NULL
				WHERE status = ?
				  AND claimed_at IS NOT NULL
				  AND claimed_at < ?
				""", StagingPlanStatus.PENDING.name(), StagingPlanStatus.PROCESSING.name(), toTs(claimedBefore));
	}

	public void markDone(final UUID stagedPlanId) {
		this.jdbcTemplate.update("""
				UPDATE staging_plans
				SET status = ?, processed_at = ?
				WHERE id = ?
				""", StagingPlanStatus.DONE.name(), toTs(Instant.now()), stagedPlanId);
	}

	public void markFailed(final UUID stagedPlanId, final String error, final int maxAttempts) {
		this.txTemplate.executeWithoutResult(status -> {
			final Integer attempts = this.jdbcTemplate.queryForObject("SELECT attempts FROM staging_plans WHERE id = ?",
					Integer.class, stagedPlanId);

			final int nextAttempts = (attempts == null ? 0 : attempts) + 1;
			final boolean terminal = nextAttempts >= maxAttempts;

			this.jdbcTemplate.update("""
					UPDATE staging_plans
					SET status = ?, attempts = ?, last_error = ?
					WHERE id = ?
					""", terminal ? StagingPlanStatus.FAILED.name() : StagingPlanStatus.PENDING.name(), nextAttempts,
					error, stagedPlanId);
		});
	}

	/**
	 * Marks all non-terminal rows for a given run as FAILED.
	 * <p>
	 * This is used when worker-fetch fails mid-stream so we don't keep PENDING rows
	 * around forever.
	 */
	public int markFailedForRun(final UUID runId, final String error) {
		if (runId == null) {
			return 0;
		}
		return this.jdbcTemplate.update("""
				UPDATE staging_plans
				SET status = ?, processed_at = ?, last_error = ?
				WHERE run_id = ?
				  AND status IN (?, ?)
				""", StagingPlanStatus.FAILED.name(), toTs(Instant.now()), error, runId,
				StagingPlanStatus.PENDING.name(), StagingPlanStatus.PROCESSING.name());
	}

	private static Timestamp toTs(final Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}

	public enum StagingPlanStatus {
		PENDING, PROCESSING, DONE, FAILED
	}
}
