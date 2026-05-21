package com.egobb.plan.service.infrastructure.scheduled;

import com.egobb.plan.service.infrastructure.persistence.postgres.ingest.StagingPlanPostgresRepository;
import com.egobb.plan.service.shared.observability.PlanServiceMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Crash-recovery guard for the DB-queue.
 * <p>
 * If a worker-process crashes after claiming rows (status=PROCESSING), those
 * rows would otherwise be stuck forever. We periodically requeue them back to
 * PENDING based on claimed_at.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mode", havingValue = "worker-process")
public class RequeueStuckStagedPlansScheduled {

	private final WorkerProcessProperties processProperties;
	private final StagingPlanPostgresRepository stagingRepository;
	private final PlanServiceMetrics metrics;

	@Scheduled(fixedDelayString = "${worker.process.requeue-interval-ms:60000}")
	public void run() {
		MDC.put("app_mode", "worker-process");
		try {
			final long ttlMs = this.processProperties.stuckTtlMsOrDefault(600_000L); // 10 min
			final Instant threshold = Instant.now().minusMillis(Math.max(1L, ttlMs));
			final int requeued = this.stagingRepository.requeueStuckProcessing(threshold);
			if (this.metrics != null) {
				this.metrics.recordRequeuedStuck(requeued);
			}
			if (requeued > 0) {
				log.warn("Requeued stuck staged plans count={} threshold={}ms", requeued, ttlMs);
			}
		} finally {
			MDC.remove("app_mode");
		}
	}
}
