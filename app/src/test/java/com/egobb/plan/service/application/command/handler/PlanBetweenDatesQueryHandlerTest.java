package com.egobb.plan.service.application.command.handler;

import com.egobb.plan.service.application.command.query.PlanBetweenDatesQuery;
import com.egobb.plan.service.application.search.PlanView;
import com.egobb.plan.service.domain.port.PlanReadRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanBetweenDatesQueryHandlerTest {

	@Mock
	private PlanReadRepository planReadRepository;

	@InjectMocks
	private PlanBetweenDatesQueryHandler handler;

	@Test
	void findPlansBetweenDates_andExistsAtLeastOne_ok() {
		final var startsAt = Optional.of(Instant.parse("2026-01-01T00:00:00Z"));
		final var endsAt = Optional.of(Instant.parse("2026-02-01T00:00:00Z"));
		final var limit = 10;
		final var offset = 0;

		final var query = PlanBetweenDatesQuery.builder().startsAt(startsAt).endsAt(endsAt).limit(limit).offset(offset)
				.build();

		final var expected = List.of(org.mockito.Mockito.mock(PlanView.class));

		when(this.planReadRepository.search(startsAt, endsAt, limit, offset)).thenReturn(expected);

		final var result = this.handler.handle(query);

		assertNotNull(result);
		assertEquals(expected, result);
	}

	@Test
	void findPlansBetweenDates_andNotExists_returnEmptyList() {
		final var startsAt = Optional.of(Instant.parse("2026-01-01T00:00:00Z"));
		final var endsAt = Optional.of(Instant.parse("2026-02-01T00:00:00Z"));
		final var limit = 10;
		final var offset = 0;

		final var query = PlanBetweenDatesQuery.builder().startsAt(startsAt).endsAt(endsAt).limit(limit).offset(offset)
				.build();

		when(this.planReadRepository.search(startsAt, endsAt, limit, offset)).thenReturn(Collections.emptyList());

		final var result = this.handler.handle(query);

		assertNotNull(result);
		assertEquals(Collections.emptyList(), result);
	}
}
