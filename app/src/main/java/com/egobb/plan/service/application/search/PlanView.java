package com.egobb.plan.service.application.search;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PlanView(UUID id, String title, Instant startsAt, Instant endsAt, BigDecimal minPrice,
		BigDecimal maxPrice) {
}
