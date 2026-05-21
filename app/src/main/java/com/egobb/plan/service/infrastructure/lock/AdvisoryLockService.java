package com.egobb.plan.service.infrastructure.lock;

public interface AdvisoryLockService {
	/**
	 * Tries to acquire the given distributed lock and, if successful, runs the
	 * critical section.
	 *
	 * @return true if the critical section was executed.
	 */
	boolean tryWithLock(String lockName, Runnable criticalSection);
}
