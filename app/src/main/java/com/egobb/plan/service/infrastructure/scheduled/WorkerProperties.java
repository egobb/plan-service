package com.egobb.plan.service.infrastructure.scheduled;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "worker")
public record WorkerProperties(Long pollIntervalMs, Lock lock) {

	public record Lock(Boolean enabled, String name) {
	}

	public boolean isLockEnabled() {
		return this.lock != null && Boolean.TRUE.equals(this.lock.enabled());
	}

	public String lockNameOrDefault() {
		if (this.lock == null || this.lock.name() == null || this.lock.name().isBlank()) {
			return "worker:poll";
		}
		return this.lock.name();
	}
}
