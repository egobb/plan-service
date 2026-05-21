package com.egobb.plan.service.domain.model.plan;

import java.util.Objects;

public record PlanKey(String providerId, String externalPlanId) {
	public PlanKey {
		Objects.requireNonNull(providerId, "providerId");
		Objects.requireNonNull(externalPlanId, "externalPlanId");
	}
}
