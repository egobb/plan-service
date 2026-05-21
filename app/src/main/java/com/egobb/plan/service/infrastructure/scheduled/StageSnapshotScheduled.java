package com.egobb.plan.service.infrastructure.scheduled;

import com.egobb.plan.service.application.command.cmd.StageSnapshotCmd;
import com.egobb.plan.service.application.command.handler.StageSnapshotCmdHandler;
import com.egobb.plan.service.infrastructure.lock.AdvisoryLockService;
import com.egobb.plan.service.infrastructure.rest.adapter.provider.ProviderRegistry;
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
@ConditionalOnProperty(name = "app.mode", havingValue = "worker-fetch")
public class StageSnapshotScheduled {

	private final WorkerFetchProperties fetchProperties;
	private final AdvisoryLockService lockService;
	private final StageSnapshotCmdHandler cmdHandler;
	private final ProviderRegistry providerRegistry;
	private final PlanServiceMetrics metrics;

	private final AtomicBoolean running = new AtomicBoolean(false);

	@Scheduled(fixedDelayString = "${worker.fetch.poll-interval-ms:30000}")
	public void run() {
		if (!this.running.compareAndSet(false, true)) {
			log.info("StageSnapshotScheduled already running, skipping.");
			if (this.metrics != null) {
				this.metrics.recordSchedulerSkip("stage_snapshot", "already_running");
			}
			return;
		}

		try {
			MDC.put("app_mode", "worker-fetch");
			final String baseLockName = this.fetchProperties.lockNameOrDefault("worker:fetch");
			final int batch = this.fetchProperties.batchSizeOrDefault(500);

			for (final String providerId : this.providerRegistry.providerIds()) {
				final String lockName = baseLockName + ":" + providerId;
				final boolean executed = this.lockService.tryWithLock(lockName, () -> {
					MDC.put("provider_id", providerId);
					try {
						final var result = this.cmdHandler.handle(new StageSnapshotCmd(providerId, batch));
						MDC.put("run_id", result.runId().toString());
						log.info("Staged runId={}, stagedPlans={}", result.runId(), result.stagedPlansCount());
					} finally {
						MDC.remove("run_id");
						MDC.remove("provider_id");
					}
				});

				if (!executed) {
					log.info("StageSnapshotScheduled lock not acquired for providerId={}, skipping.", providerId);
					if (this.metrics != null) {
						this.metrics.recordSchedulerSkip("stage_snapshot", "lock_not_acquired");
					}
				}
			}
		} finally {
			MDC.remove("app_mode");
			this.running.set(false);
		}
	}
}
