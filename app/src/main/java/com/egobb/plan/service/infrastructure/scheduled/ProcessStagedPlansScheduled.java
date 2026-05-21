package com.egobb.plan.service.infrastructure.scheduled;

import com.egobb.plan.service.application.command.cmd.ProcessStagedPlansCmd;
import com.egobb.plan.service.application.command.handler.ProcessStagedPlansCmdHandler;
import com.egobb.plan.service.shared.observability.PlanServiceMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.mode", havingValue = "worker-process")
public class ProcessStagedPlansScheduled {

	private final WorkerProcessProperties processProperties;
	private final ProcessStagedPlansCmdHandler cmdHandler;
	private final PlanServiceMetrics metrics;

	private final AtomicBoolean running = new AtomicBoolean(false);

	@Scheduled(fixedDelayString = "${worker.process.poll-interval-ms:30000}")
	public void run() {
		if (!this.running.compareAndSet(false, true)) {
			log.info("ProcessStagedPlansScheduled already running, skipping.");
			if (this.metrics != null) {
				this.metrics.recordSchedulerSkip("process_staged_plans", "already_running");
			}
			return;
		}

		try {
			MDC.put("app_mode", "worker-process");

			// IMPORTANT:
			// Do NOT take a distributed lock here.
			// Horizontal scaling is achieved via DB-queue semantics using
			// SELECT ... FOR UPDATE SKIP LOCKED in StagingPlanPostgresRepository.
			final int batch = this.processProperties.batchSizeOrDefault(200);
			final int maxAttempts = this.processProperties.maxAttemptsOrDefault(5);
			final int processed = this.cmdHandler.handle(new ProcessStagedPlansCmd(batch, maxAttempts));
			log.info("Processed staged plans count={}", processed);
		} finally {
			MDC.remove("app_mode");
			this.running.set(false);
		}
	}
}
