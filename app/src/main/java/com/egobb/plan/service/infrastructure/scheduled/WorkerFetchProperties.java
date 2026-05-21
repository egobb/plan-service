package com.egobb.plan.service.infrastructure.scheduled;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "worker.fetch")
public record WorkerFetchProperties(Long pollIntervalMs, Integer batchSize, String lockName) {

	public Long pollIntervalMsOrDefault(final long def) {
		return this.pollIntervalMs == null ? def : this.pollIntervalMs;
	}

	public Integer batchSizeOrDefault(final int def) {
		return this.batchSize == null ? def : this.batchSize;
	}

	public String lockNameOrDefault(final String def) {
		return (this.lockName == null || this.lockName.isBlank()) ? def : this.lockName;
	}
}
