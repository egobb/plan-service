package com.egobb.plan.service.domain.service;

import com.egobb.plan.service.application.ingest.ProviderPlan;
import com.egobb.plan.service.domain.port.ProviderPort;
import com.egobb.plan.service.infrastructure.persistence.postgres.ingest.IngestionRunPostgresRepository;
import com.egobb.plan.service.infrastructure.persistence.postgres.ingest.StagingPlanPostgresRepository;
import com.egobb.plan.service.infrastructure.rest.adapter.provider.ProviderRegistry;
import com.egobb.plan.service.shared.observability.PlanServiceMetrics;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class StageSnapshotService {

	private final ProviderRegistry providerRegistry;
	private final IngestionRunPostgresRepository runRepository;
	private final StagingPlanPostgresRepository stagingRepository;

	private final PlanServiceMetrics metrics;

	public StageSnapshotService(final ProviderRegistry providerRegistry,
			final IngestionRunPostgresRepository runRepository, final StagingPlanPostgresRepository stagingRepository,
			final PlanServiceMetrics metrics) {
		this.providerRegistry = providerRegistry;
		this.runRepository = runRepository;
		this.stagingRepository = stagingRepository;
		this.metrics = metrics;
	}

	public StageResult stage(final String providerId, final int batchSize) {
		final Instant now = Instant.now();
		final UUID runId = this.runRepository.createRun(providerId, now);

		final boolean setProvider = putIfAbsent("provider_id", providerId);
		final boolean setRun = putIfAbsent("run_id", runId.toString());

		final Instant start = Instant.now();

		try {
			final ProviderPort providerPort = this.providerRegistry.getRequired(providerId);

			final int safeBatchSize = Math.max(1, batchSize);
			final List<ProviderPlan> buffer = new ArrayList<>(safeBatchSize);

			providerPort.streamPlans(plan -> {
				buffer.add(plan);
				if (buffer.size() >= safeBatchSize) {
					this.stagingRepository.insertPlanBatch(runId, providerId, List.copyOf(buffer), Instant.now());
					if (this.metrics != null) {
						this.metrics.recordStagingInsertBatch(providerId, buffer.size());
					}
					buffer.clear();
				}
			});

			if (!buffer.isEmpty()) {
				this.stagingRepository.insertPlanBatch(runId, providerId, List.copyOf(buffer), Instant.now());
				if (this.metrics != null) {
					this.metrics.recordStagingInsertBatch(providerId, buffer.size());
				}
				buffer.clear();
			}

			final int staged = this.stagingRepository.countForRun(runId);
			this.runRepository.markStaged(runId, Instant.now(), staged);

			final long ms = Duration.between(start, Instant.now()).toMillis();
			// log is helpful for operational visibility
			org.slf4j.LoggerFactory.getLogger(StageSnapshotService.class).info(
					"Stage snapshot completed: providerId={} runId={} staged={} durationMs={}", providerId, runId,
					staged, ms);
			if (this.metrics != null) {
				this.metrics.recordStageSnapshot(providerId, staged, Duration.between(start, Instant.now()));
			}

			return new StageResult(runId, staged);

		} catch (final Exception e) {
			final String err = safeMessage(e);
			this.runRepository.markFailed(runId, Instant.now(), err);
			// Ensure partially staged rows are not left as PENDING forever.
			this.stagingRepository.markFailedForRun(runId, err);

			final long ms = Duration.between(start, Instant.now()).toMillis();
			org.slf4j.LoggerFactory.getLogger(StageSnapshotService.class).error(
					"Stage snapshot failed: providerId={} runId={} durationMs={} err={}", providerId, runId, ms, e, e);
			if (this.metrics != null) {
				this.metrics.recordStageSnapshotFailure(providerId, Duration.between(start, Instant.now()));
			}

			return new StageResult(runId, 0);
		} finally {
			if (setRun)
				MDC.remove("run_id");
			if (setProvider)
				MDC.remove("provider_id");
		}
	}

	private static boolean putIfAbsent(final String key, final String value) {
		if (value == null)
			return false;
		if (MDC.get(key) != null)
			return false;
		MDC.put(key, value);
		return true;
	}

	private static String safeMessage(final Exception e) {
		final String msg = e.getMessage();
		if (msg == null)
			return e.getClass().getSimpleName();
		return msg.length() > 2000 ? msg.substring(0, 2000) : msg;
	}

	public record StageResult(UUID runId, int stagedPlansCount) {
	}
}
