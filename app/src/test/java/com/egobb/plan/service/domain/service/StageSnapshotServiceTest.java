package com.egobb.plan.service.domain.service;

import com.egobb.plan.service.application.ingest.ProviderPlan;
import com.egobb.plan.service.domain.model.plan.SellMode;
import com.egobb.plan.service.domain.port.ProviderPort;
import com.egobb.plan.service.infrastructure.persistence.postgres.ingest.IngestionRunPostgresRepository;
import com.egobb.plan.service.infrastructure.persistence.postgres.ingest.StagingPlanPostgresRepository;
import com.egobb.plan.service.infrastructure.rest.adapter.provider.ProviderRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StageSnapshotServiceTest {

	@Test
	void shouldStreamAndStageInBatches_andMarkRunStaged() {
		final ProviderRegistry providerRegistry = mock(ProviderRegistry.class);
		final ProviderPort providerPort = mock(ProviderPort.class);
		final IngestionRunPostgresRepository runRepo = mock(IngestionRunPostgresRepository.class);
		final StagingPlanPostgresRepository stagingRepo = mock(StagingPlanPostgresRepository.class);

		when(providerRegistry.getRequired("demo-provider")).thenReturn(providerPort);

		final UUID runId = UUID.randomUUID();
		when(runRepo.createRun(eq("demo-provider"), any(Instant.class))).thenReturn(runId);
		when(stagingRepo.countForRun(runId)).thenReturn(1200);

		// Stream 1200 plans
		doAnswer(inv -> {
			@SuppressWarnings("unchecked")
			final Consumer<ProviderPlan> consumer = inv.getArgument(0);
			for (int i = 0; i < 1200; i++) {
				consumer.accept(new ProviderPlan(String.valueOf(i), "T" + i, SellMode.ONLINE,
						Instant.parse("2024-01-01T10:00:00Z"), null, null, null));
			}
			return null;
		}).when(providerPort).streamPlans(any());

		final StageSnapshotService svc = new StageSnapshotService(providerRegistry, runRepo, stagingRepo, null);
		final StageSnapshotService.StageResult result = svc.stage("demo-provider", 500);

		assertThat(result.runId()).isEqualTo(runId);
		assertThat(result.stagedPlansCount()).isEqualTo(1200);

		// 1200 plans with batchSize=500 -> 3 inserts
		final ArgumentCaptor<List<ProviderPlan>> batchCaptor = ArgumentCaptor.forClass(List.class);
		verify(stagingRepo, times(3)).insertPlanBatch(eq(runId), eq("demo-provider"), batchCaptor.capture(),
				any(Instant.class));

		final List<List<ProviderPlan>> batches = batchCaptor.getAllValues();
		assertThat(batches).hasSize(3);
		assertThat(batches.get(0)).hasSize(500);
		assertThat(batches.get(1)).hasSize(500);
		assertThat(batches.get(2)).hasSize(200);

		verify(runRepo, times(1)).markStaged(eq(runId), any(Instant.class), eq(1200));
		verify(runRepo, never()).markFailed(any(), any(), anyString());
	}

	@Test
	void shouldUseSafeBatchSizeWhenBatchSizeIsZeroOrNegative() {
		final ProviderRegistry providerRegistry = mock(ProviderRegistry.class);
		final ProviderPort providerPort = mock(ProviderPort.class);
		final IngestionRunPostgresRepository runRepo = mock(IngestionRunPostgresRepository.class);
		final StagingPlanPostgresRepository stagingRepo = mock(StagingPlanPostgresRepository.class);

		when(providerRegistry.getRequired("demo-provider")).thenReturn(providerPort);

		final UUID runId = UUID.randomUUID();
		when(runRepo.createRun(eq("demo-provider"), any(Instant.class))).thenReturn(runId);

		// Stream 3 plans -> with safeBatchSize=1 we should insert 3 times
		doAnswer(inv -> {
			@SuppressWarnings("unchecked")
			final Consumer<ProviderPlan> consumer = inv.getArgument(0);
			for (int i = 0; i < 3; i++) {
				consumer.accept(new ProviderPlan(String.valueOf(i), "T" + i, SellMode.ONLINE,
						Instant.parse("2024-01-01T10:00:00Z"), null, null, null));
			}
			return null;
		}).when(providerPort).streamPlans(any());

		when(stagingRepo.countForRun(runId)).thenReturn(3);

		final StageSnapshotService svc = new StageSnapshotService(providerRegistry, runRepo, stagingRepo, null);
		final StageSnapshotService.StageResult result = svc.stage("demo-provider", 0); // <= 0 => safeBatchSize=1

		assertThat(result.stagedPlansCount()).isEqualTo(3);

		verify(stagingRepo, times(3)).insertPlanBatch(eq(runId), eq("demo-provider"), anyList(), any(Instant.class));
		verify(runRepo).markStaged(eq(runId), any(Instant.class), eq(3));
	}

	@Test
	void shouldMarkRunFailedIfProviderThrows_beforeAnyInsert() {
		final ProviderRegistry providerRegistry = mock(ProviderRegistry.class);
		final ProviderPort providerPort = mock(ProviderPort.class);
		final IngestionRunPostgresRepository runRepo = mock(IngestionRunPostgresRepository.class);
		final StagingPlanPostgresRepository stagingRepo = mock(StagingPlanPostgresRepository.class);

		when(providerRegistry.getRequired("demo-provider")).thenReturn(providerPort);

		final UUID runId = UUID.randomUUID();
		when(runRepo.createRun(eq("demo-provider"), any(Instant.class))).thenReturn(runId);

		doThrow(new RuntimeException("boom")).when(providerPort).streamPlans(any());

		final StageSnapshotService svc = new StageSnapshotService(providerRegistry, runRepo, stagingRepo, null);
		final StageSnapshotService.StageResult result = svc.stage("demo-provider", 100);

		assertThat(result.runId()).isEqualTo(runId);
		assertThat(result.stagedPlansCount()).isEqualTo(0);

		verify(runRepo).markFailed(eq(runId), any(Instant.class), contains("boom"));
		verify(stagingRepo, never()).insertPlanBatch(any(), anyString(), anyList(), any());
		verify(runRepo, never()).markStaged(any(), any(), anyInt());
	}

	@Test
	void shouldMarkRunFailedIfInsertThrows_midStream() {
		final ProviderRegistry providerRegistry = mock(ProviderRegistry.class);
		final ProviderPort providerPort = mock(ProviderPort.class);
		final IngestionRunPostgresRepository runRepo = mock(IngestionRunPostgresRepository.class);
		final StagingPlanPostgresRepository stagingRepo = mock(StagingPlanPostgresRepository.class);

		when(providerRegistry.getRequired("demo-provider")).thenReturn(providerPort);

		final UUID runId = UUID.randomUUID();
		when(runRepo.createRun(eq("demo-provider"), any(Instant.class))).thenReturn(runId);

		// Stream enough to trigger at least one insert
		doAnswer(inv -> {
			@SuppressWarnings("unchecked")
			final Consumer<ProviderPlan> consumer = inv.getArgument(0);
			for (int i = 0; i < 10; i++) {
				consumer.accept(new ProviderPlan(String.valueOf(i), "T" + i, SellMode.ONLINE,
						Instant.parse("2024-01-01T10:00:00Z"), null, null, null));
			}
			return null;
		}).when(providerPort).streamPlans(any());

		// Fail on first insert
		doThrow(new RuntimeException("db down")).when(stagingRepo).insertPlanBatch(eq(runId), eq("demo-provider"),
				anyList(), any(Instant.class));

		final StageSnapshotService svc = new StageSnapshotService(providerRegistry, runRepo, stagingRepo, null);
		final StageSnapshotService.StageResult result = svc.stage("demo-provider", 5);

		assertThat(result.runId()).isEqualTo(runId);
		assertThat(result.stagedPlansCount()).isEqualTo(0);

		verify(runRepo).markFailed(eq(runId), any(Instant.class), contains("db down"));
		verify(runRepo, never()).markStaged(any(), any(), anyInt());
		// countForRun should not be called on failure
		verify(stagingRepo, never()).countForRun(any());
	}

}
