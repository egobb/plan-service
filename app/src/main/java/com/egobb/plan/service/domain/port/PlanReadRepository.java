package com.egobb.plan.service.domain.port;

import com.egobb.plan.service.application.search.PlanView;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PlanReadRepository {
	List<PlanView> search(Optional<Instant> startsAt, Optional<Instant> endsAt, int limit, int offset);
}
