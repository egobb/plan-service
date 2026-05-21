package com.egobb.plan.service.infrastructure.scheduled;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "worker.process")
public record WorkerProcessProperties(Long pollIntervalMs, Integer batchSize, Integer maxAttempts,
		Long requeueIntervalMs, Long stuckTtlMs) {

	public Long pollIntervalMsOrDefault(final long def) {
		return this.pollIntervalMs == null ? def : this.pollIntervalMs;
	}

	public Integer batchSizeOrDefault(final int def) {
		return this.batchSize == null ? def : this.batchSize;
	}

	public Integer maxAttemptsOrDefault(final int def) {
		return this.maxAttempts == null ? def : this.maxAttempts;
	}

	public Long requeueIntervalMsOrDefault(final long def) {
		return this.requeueIntervalMs == null ? def : this.requeueIntervalMs;
	}

	public Long stuckTtlMsOrDefault(final long def) {
		return this.stuckTtlMs == null ? def : this.stuckTtlMs;
	}
}
