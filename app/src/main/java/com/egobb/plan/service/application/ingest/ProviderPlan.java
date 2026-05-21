package com.egobb.plan.service.application.ingest;

import com.egobb.plan.service.domain.model.plan.SellMode;

import java.math.BigDecimal;
import java.time.Instant;

public record ProviderPlan(String externalPlanId, String title, SellMode sellMode, Instant startsAt, Instant endsAt,
		BigDecimal minPrice, BigDecimal maxPrice) {
}
