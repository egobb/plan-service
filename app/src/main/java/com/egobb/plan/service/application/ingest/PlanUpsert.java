package com.egobb.plan.service.application.ingest;

import com.egobb.plan.service.domain.model.plan.SellMode;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * DTO used by PlanWriteRepository to upsert plans into the final model.
 *
 * NOTE: timestamps (first_seen_at / last_seen_at) are managed by the repository
 * using the "seenAt" argument. The "everOnline" flag represents whether this
 * snapshot sees the plan as ONLINE; the repository must persist it as sticky.
 */
public record PlanUpsert(String providerId, String externalPlanId, String title, Instant startsAt, Instant endsAt,
		BigDecimal minPrice, BigDecimal maxPrice, boolean everOnline, SellMode lastSellMode) {
}
