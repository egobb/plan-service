package com.egobb.plan.service.application.command.query;

import com.egobb.plan.service.shared.command.Query;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Optional;

@Value
@Builder(toBuilder = true)
public class PlanBetweenDatesQuery implements Query {

	Optional<Instant> startsAt;

	Optional<Instant> endsAt;

	int limit;

	int offset;
}
