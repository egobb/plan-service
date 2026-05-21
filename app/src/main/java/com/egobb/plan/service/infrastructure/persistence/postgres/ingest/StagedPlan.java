package com.egobb.plan.service.infrastructure.persistence.postgres.ingest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StagedPlan(UUID id, UUID runId, String providerId, String externalPlanId, String title, String sellMode,
		Instant startsAt, Instant endsAt, BigDecimal minPrice, BigDecimal maxPrice,
		/**
		 * Snapshot "seen at" timestamp. We use the ingestion run's started_at to keep
		 * correctness even if processing happens later or out-of-order.
		 */
		Instant runStartedAt, int attempts) {
}
