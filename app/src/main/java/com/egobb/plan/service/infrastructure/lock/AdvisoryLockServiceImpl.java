package com.egobb.plan.service.infrastructure.lock;

import com.egobb.plan.service.infrastructure.scheduled.WorkerProperties;
import org.springframework.stereotype.Component;

@Component
public class AdvisoryLockServiceImpl implements AdvisoryLockService {

	private final WorkerProperties workerProperties;
	private final PostgresAdvisoryLockService postgresAdvisoryLockService;

	public AdvisoryLockServiceImpl(final WorkerProperties workerProperties,
			final PostgresAdvisoryLockService postgresAdvisoryLockService) {
		this.workerProperties = workerProperties;
		this.postgresAdvisoryLockService = postgresAdvisoryLockService;
	}

	@Override
	public boolean tryWithLock(final String lockName, final Runnable criticalSection) {
		if (!this.workerProperties.isLockEnabled()) {
			criticalSection.run();
			return true;
		}

		final var handleOpt = this.postgresAdvisoryLockService.tryLock(lockName);
		if (handleOpt.isEmpty()) {
			return false;
		}

		try (final PostgresAdvisoryLockService.LockHandle ignored = handleOpt.get()) {
			criticalSection.run();
			return true;
		}
	}
}
