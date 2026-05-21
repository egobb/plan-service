package com.egobb.plan.service.shared.observability;

import io.micrometer.core.instrument.*;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal, low-cardinality metrics focused on operability.
 *
 * Naming convention: - egobb_* for custom domain metrics - provider_id is
 * allowed (small cardinality)
 */
public class PlanServiceMetrics {

	private final MeterRegistry registry;

	private final Map<String, Counter> counters = new ConcurrentHashMap<>();
	private final Map<String, Timer> timers = new ConcurrentHashMap<>();
	private final Map<String, DistributionSummary> summaries = new ConcurrentHashMap<>();

	public PlanServiceMetrics(final MeterRegistry registry) {
		this.registry = registry;
	}

	public Timer.Sample startTimer() {
		return Timer.start(this.registry);
	}

	public void recordProviderSnapshot(final String providerId, final String outcome, final long plansCount,
			final Duration duration) {
		this.counter("egobb_provider_snapshot_total", Tags.of("provider_id", providerId, "outcome", outcome))
				.increment();
		this.summary("egobb_provider_snapshot_plans", Tags.of("provider_id", providerId, "outcome", outcome))
				.record(plansCount);
		if (duration != null) {
			this.timer("egobb_provider_snapshot_duration", Tags.of("provider_id", providerId, "outcome", outcome))
					.record(duration);
		}
	}

	public void recordStageSnapshot(final String providerId, final int stagedPlans, final Duration duration) {
		this.counter("egobb_stage_snapshot_total", Tags.of("provider_id", providerId, "outcome", "success"))
				.increment();
		this.summary("egobb_stage_snapshot_staged_plans", Tags.of("provider_id", providerId)).record(stagedPlans);
		if (duration != null) {
			this.timer("egobb_stage_snapshot_duration", Tags.of("provider_id", providerId)).record(duration);
		}
	}

	public void recordStageSnapshotFailure(final String providerId, final Duration duration) {
		this.counter("egobb_stage_snapshot_total", Tags.of("provider_id", providerId, "outcome", "failure"))
				.increment();
		if (duration != null) {
			this.timer("egobb_stage_snapshot_duration", Tags.of("provider_id", providerId)).record(duration);
		}
	}

	public void recordStagingInsertBatch(final String providerId, final int batchSize) {
		this.counter("egobb_staging_insert_batch_total", Tags.of("provider_id", providerId)).increment();
		this.summary("egobb_staging_insert_batch_size", Tags.of("provider_id", providerId)).record(batchSize);
	}

	public void recordProcessPlan(final String providerId, final String outcome, final Duration duration) {
		this.counter("egobb_process_plan_total", Tags.of("provider_id", providerId, "outcome", outcome)).increment();
		if (duration != null) {
			this.timer("egobb_process_plan_duration", Tags.of("provider_id", providerId, "outcome", outcome))
					.record(duration);
		}
	}

	public void recordSchedulerSkip(final String scheduler, final String reason) {
		this.counter("egobb_scheduler_skips_total", Tags.of("scheduler", scheduler, "reason", reason)).increment();
	}

	public void recordSearchRejected(final String reason) {
		final String r = (reason == null || reason.isBlank()) ? "unknown" : reason;
		this.counter("egobb_search_rejected_total", Tags.of("reason", r)).increment();
	}

	public void recordRequeuedStuck(final int count) {
		if (count <= 0) {
			return;
		}
		this.counter("egobb_staging_requeued_total", Tags.empty()).increment(count);
	}

	private Counter counter(final String name, final Tags tags) {
		final String key = name + "|" + tags;
		return this.counters.computeIfAbsent(key, k -> Counter.builder(name).tags(tags).register(this.registry));
	}

	private Timer timer(final String name, final Tags tags) {
		final String key = name + "|" + tags;
		return this.timers.computeIfAbsent(key,
				k -> Timer.builder(name).tags(tags).publishPercentileHistogram().register(this.registry));
	}

	private DistributionSummary summary(final String name, final Tags tags) {
		final String key = name + "|" + tags;
		return this.summaries.computeIfAbsent(key,
				k -> DistributionSummary.builder(name).tags(tags).publishPercentileHistogram().register(this.registry));
	}
}
