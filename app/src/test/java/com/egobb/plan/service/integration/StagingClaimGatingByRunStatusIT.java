package com.egobb.plan.service.integration;

import com.egobb.plan.service.application.ingest.ProviderPlan;
import com.egobb.plan.service.domain.model.plan.SellMode;
import com.egobb.plan.service.infrastructure.persistence.postgres.ingest.IngestionRunPostgresRepository;
import com.egobb.plan.service.infrastructure.persistence.postgres.ingest.StagingPlanPostgresRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"spring.sql.init.mode=always"})
class StagingClaimGatingByRunStatusIT extends PostgresTestcontainerConfig {

	@Autowired
	IngestionRunPostgresRepository runRepo;

	@Autowired
	StagingPlanPostgresRepository stagingRepo;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@BeforeEach
	void cleanup() {
		this.jdbcTemplate.execute("TRUNCATE TABLE staging_plans CASCADE");
		this.jdbcTemplate.execute("TRUNCATE TABLE ingestion_runs CASCADE");
	}

	@Test
	void claimNextAnyProvider_shouldIgnoreRowsIfRunIsNotStaged() {
		final Instant now = Instant.parse("2026-01-01T00:00:00Z");
		final String providerId = "demo-provider";
		final UUID runId = this.runRepo.createRun(providerId, now);

		this.stagingRepo.insertPlanBatch(runId, providerId,
				List.of(new ProviderPlan("P-1", "A", SellMode.ONLINE, Instant.parse("2026-01-10T10:00:00Z"), null, null,
						null),
						new ProviderPlan("P-2", "B", SellMode.OFFLINE, Instant.parse("2026-01-11T10:00:00Z"), null,
								null, null)),
				now);

		// Run has NOT been marked STAGED -> must NOT be claimed.
		final var claimed = this.stagingRepo.claimNextAnyProvider(10, 3);
		assertThat(claimed).isEmpty();

		final Integer pending = this.jdbcTemplate
				.queryForObject("SELECT COUNT(*) FROM staging_plans WHERE status='PENDING'", Integer.class);
		assertThat(pending).isEqualTo(2);

		final Integer processing = this.jdbcTemplate
				.queryForObject("SELECT COUNT(*) FROM staging_plans WHERE status='PROCESSING'", Integer.class);
		assertThat(processing).isEqualTo(0);

		final Integer claimedAtNotNull = this.jdbcTemplate
				.queryForObject("SELECT COUNT(*) FROM staging_plans WHERE claimed_at IS NOT NULL", Integer.class);
		assertThat(claimedAtNotNull).isEqualTo(0);
	}

	@Test
	void claimNextAnyProvider_shouldClaimOnlyWhenRunIsStaged_andUpdateDbState() {
		final Instant now = Instant.parse("2026-01-01T00:00:00Z");
		final String providerId = "demo-provider";
		final UUID runId = this.runRepo.createRun(providerId, now);

		this.stagingRepo.insertPlanBatch(runId, providerId,
				List.of(new ProviderPlan("P-1", "A", SellMode.ONLINE, Instant.parse("2026-01-10T10:00:00Z"), null, null,
						null),
						new ProviderPlan("P-2", "B", SellMode.OFFLINE, Instant.parse("2026-01-11T10:00:00Z"), null,
								null, null)),
				now);

		// Mark run STAGED -> now rows become eligible for claiming.
		final int stagedCount = this.stagingRepo.countForRun(runId);
		this.runRepo.markStaged(runId, Instant.parse("2026-01-01T00:00:10Z"), stagedCount);

		final var claimed = this.stagingRepo.claimNextAnyProvider(10, 3);
		assertThat(claimed).hasSize(2);

		// DB evidence: claimed rows must be PROCESSING and claimed_at not null.
		final Integer processing = this.jdbcTemplate
				.queryForObject("SELECT COUNT(*) FROM staging_plans WHERE status='PROCESSING'", Integer.class);
		assertThat(processing).isEqualTo(2);

		final Integer claimedAtNotNull = this.jdbcTemplate
				.queryForObject("SELECT COUNT(*) FROM staging_plans WHERE claimed_at IS NOT NULL", Integer.class);
		assertThat(claimedAtNotNull).isEqualTo(2);

		// And no PENDING rows left for that run
		final Integer pending = this.jdbcTemplate
				.queryForObject("SELECT COUNT(*) FROM staging_plans WHERE status='PENDING'", Integer.class);
		assertThat(pending).isEqualTo(0);
	}

	@Test
	void claimNextAnyProvider_shouldNotClaimRowsFromFailedRun() {
		final Instant now = Instant.parse("2026-01-01T00:00:00Z");
		final String providerId = "demo-provider";
		final UUID runId = this.runRepo.createRun(providerId, now);

		this.stagingRepo.insertPlanBatch(runId, providerId, List.of(
				new ProviderPlan("P-1", "A", SellMode.ONLINE, Instant.parse("2026-01-10T10:00:00Z"), null, null, null)),
				now);

		this.runRepo.markFailed(runId, Instant.parse("2026-01-01T00:00:10Z"), "boom");

		final var claimed = this.stagingRepo.claimNextAnyProvider(10, 3);
		assertThat(claimed).isEmpty();

		final Integer processing = this.jdbcTemplate
				.queryForObject("SELECT COUNT(*) FROM staging_plans WHERE status='PROCESSING'", Integer.class);
		assertThat(processing).isEqualTo(0);
	}
}
