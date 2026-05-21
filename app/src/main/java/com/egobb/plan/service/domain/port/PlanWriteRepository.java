package com.egobb.plan.service.domain.port;

import com.egobb.plan.service.application.ingest.PlanUpsert;

import java.time.Instant;
import java.util.List;

public interface PlanWriteRepository {
	void upsertAll(List<PlanUpsert> upserts, Instant seenAt);
}
