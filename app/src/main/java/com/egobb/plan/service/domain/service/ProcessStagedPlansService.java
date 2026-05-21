package com.egobb.plan.service.domain.service;

import com.egobb.plan.service.application.ingest.PlanUpsert;
import com.egobb.plan.service.domain.model.plan.SellMode;
import com.egobb.plan.service.domain.port.PlanWriteRepository;
import com.egobb.plan.service.infrastructure.persistence.postgres.ingest.StagedPlan;
import com.egobb.plan.service.infrastructure.persistence.postgres.ingest.StagingPlanPostgresRepository;
import com.egobb.plan.service.shared.observability.PlanServiceMetrics;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class ProcessStagedPlansService {

	private final StagingPlanPostgresRepository stagingRepository;
	private final PlanWriteRepository planWriteRepository;

	private final PlanServiceMetrics metrics;

	public ProcessStagedPlansService(final StagingPlanPostgresRepository stagingRepository,
			final PlanWriteRepository planWriteRepository, final PlanServiceMetrics metrics) {
		this.stagingRepository = stagingRepository;
		this.planWriteRepository = planWriteRepository;
		this.metrics = metrics;
	}

	public int processBatch(final int batchSize, final int maxAttempts) {
		final int safeBatchSize = Math.max(1, batchSize);
		final int safeMaxAttempts = Math.max(1, maxAttempts);

		final List<StagedPlan> claimed = this.stagingRepository.claimNextAnyProvider(safeBatchSize, safeMaxAttempts);
		if (claimed.isEmpty())
			return 0;

		int processed = 0;
		for (final StagedPlan staged : claimed) {
			this.processOne(staged, safeMaxAttempts);
			processed++;
		}

		return processed;
	}

	private void processOne(final StagedPlan staged, final int maxAttempts) {
		final Instant start = Instant.now();
		final Instant seenAt = (staged.runStartedAt() != null) ? staged.runStartedAt() : Instant.now();

		MDC.put("provider_id", staged.providerId());
		MDC.put("run_id", staged.runId().toString());
		try {
			final SellMode sellMode = SellMode.from(staged.sellMode());
			final boolean everOnlineCurrent = SellMode.ONLINE.equals(sellMode);

			final PlanUpsert upsert = new PlanUpsert(staged.providerId(), staged.externalPlanId(), staged.title(),
					staged.startsAt(), staged.endsAt(), staged.minPrice(), staged.maxPrice(), everOnlineCurrent,
					sellMode);

			this.planWriteRepository.upsertAll(List.of(upsert), seenAt);
			this.stagingRepository.markDone(staged.id());
			if (this.metrics != null) {
				this.metrics.recordProcessPlan(staged.providerId(), "success",
						java.time.Duration.between(start, Instant.now()));
			}

		} catch (final Exception e) {
			if (this.metrics != null) {
				this.metrics.recordProcessPlan(staged.providerId(), "failure",
						java.time.Duration.between(start, Instant.now()));
			}
			this.stagingRepository.markFailed(staged.id(), safeMessage(e), maxAttempts);
		} finally {
			MDC.remove("run_id");
			MDC.remove("provider_id");
		}
	}

	private static String safeMessage(final Exception e) {
		final String msg = e.getMessage();
		if (msg == null)
			return e.getClass().getSimpleName();
		return msg.length() > 2000 ? msg.substring(0, 2000) : msg;
	}
}
