package com.egobb.plan.service.integration;

import com.egobb.plan.service.application.ingest.ProviderPlan;
import com.egobb.plan.service.domain.model.plan.SellMode;
import com.egobb.plan.service.domain.service.ProcessStagedPlansService;
import com.egobb.plan.service.infrastructure.persistence.postgres.PlanPostgresAdapter;
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

@SpringBootTest(properties = {"spring.sql.init.mode=always", "provider.id=demo-provider"})
class StagingProcessingIT extends PostgresTestcontainerConfig {

	@Autowired
	IngestionRunPostgresRepository runRepo;
	@Autowired
	StagingPlanPostgresRepository stagingRepo;
	@Autowired
	ProcessStagedPlansService processSvc;
	@Autowired
	PlanPostgresAdapter planRepo;
	@Autowired
	JdbcTemplate jdbcTemplate;

	@BeforeEach
	void cleanup() {
		this.jdbcTemplate.execute("TRUNCATE TABLE staging_plans CASCADE");
		this.jdbcTemplate.execute("TRUNCATE TABLE ingestion_runs CASCADE");
		this.jdbcTemplate.execute("TRUNCATE TABLE plans CASCADE");
	}

	@Test
	void shouldProcessStagedPlansAndUpsertIntoFinalTable() {
		final Instant now = Instant.parse("2024-01-01T00:00:00Z");
		final UUID runId = this.runRepo.createRun("demo-provider", now);

		final List<ProviderPlan> plans = List.of(
				new ProviderPlan("1", "A", SellMode.OFFLINE, Instant.parse("2024-01-01T10:00:00Z"),
						Instant.parse("2024-01-01T11:00:00Z"), null, null),
				new ProviderPlan("2", "B", SellMode.OFFLINE, Instant.parse("2024-01-02T10:00:00Z"), null, null, null));

		this.stagingRepo.insertPlanBatch(runId, "demo-provider", plans, now);
		this.runRepo.markStaged(runId, now, this.stagingRepo.countForRun(runId));

		final int processed = this.processSvc.processBatch(10, 3);
		assertThat(processed).isEqualTo(2);

		final var found = this.planRepo.search(java.util.Optional.empty(), java.util.Optional.empty(), 100, 0);
		assertThat(found).hasSize(0);

		this.jdbcTemplate.update(
				"UPDATE staging_plans SET status='PENDING', attempts=0, sell_mode='online' WHERE external_plan_id='1'");
		this.jdbcTemplate.update(
				"UPDATE staging_plans SET status='PENDING', attempts=0, sell_mode='online' WHERE external_plan_id='2'");

		final var processed2 = this.processSvc.processBatch(10, 3);
		final var found2 = this.planRepo.search(java.util.Optional.empty(), java.util.Optional.empty(), 100, 0);
		assertThat(processed2).isEqualTo(2);
		assertThat(found2).hasSize(2);
	}

	@Test
	void shouldNotProcessPlansFromFailedOrRunningRun_preventsPartialSnapshotConsumption() {
		final Instant now = Instant.parse("2024-01-01T00:00:00Z");
		final UUID runId = this.runRepo.createRun("demo-provider", now);

		final List<ProviderPlan> plans = List.of(
				new ProviderPlan("1", "A", SellMode.ONLINE, Instant.parse("2024-01-01T10:00:00Z"), null, null, null),
				new ProviderPlan("2", "B", SellMode.ONLINE, Instant.parse("2024-01-02T10:00:00Z"), null, null, null));

		this.stagingRepo.insertPlanBatch(runId, "demo-provider", plans, now);

		// IMPORTANT: run is NOT marked as STAGED. Simulate a partial/failed ingestion.
		this.runRepo.markFailed(runId, now, "boom");

		final int processed = this.processSvc.processBatch(10, 3);
		assertThat(processed).isEqualTo(0);

		final Integer pending = this.jdbcTemplate
				.queryForObject("SELECT COUNT(*) FROM staging_plans WHERE status='PENDING'", Integer.class);
		assertThat(pending).isEqualTo(2);

		final Integer plansCount = this.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM plans", Integer.class);
		assertThat(plansCount).isEqualTo(0);
	}
}
