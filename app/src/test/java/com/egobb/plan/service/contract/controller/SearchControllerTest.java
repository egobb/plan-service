package com.egobb.plan.service.contract.controller;

import com.egobb.plan.service.application.command.handler.PlanBetweenDatesQueryHandler;
import com.egobb.plan.service.application.command.query.PlanBetweenDatesQuery;
import com.egobb.plan.service.application.search.PlanView;
import com.egobb.plan.service.contract.config.ApiProperties;
import com.egobb.plan.service.contract.controller.dto.ApiResponse;
import com.egobb.plan.service.contract.controller.dto.EventSummary;
import com.egobb.plan.service.contract.controller.dto.SearchData;
import com.egobb.plan.service.contract.resilience.SearchBulkheadExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SearchControllerTest {

	@Mock
	private PlanBetweenDatesQueryHandler planBetweenDatesQueryHandler;

	@Mock
	private ApiProperties apiProperties;

	@Mock
	private SearchBulkheadExecutor bulkheadExecutor;

	@InjectMocks
	private SearchController controller;

	private void stubBulkheadPassthrough() {
		when(this.bulkheadExecutor.execute(any(Supplier.class))).thenAnswer(inv -> {
			final Supplier<?> s = inv.getArgument(0);
			return s.get();
		});
	}

	@Test
	void search_ok_parsesOffsetDateTimeParams() {
		this.stubBulkheadPassthrough();
		when(this.apiProperties.zoneIdOrUtc()).thenReturn(ZoneId.of("UTC"));

		final var startsAt = "2026-01-01T00:00:00Z";
		final var endsAt = "2026-02-01T00:00:00Z";

		final var plan1 = mock(PlanView.class);
		final var plan2 = mock(PlanView.class);

		when(this.planBetweenDatesQueryHandler.handle(any(PlanBetweenDatesQuery.class)))
				.thenReturn(List.of(plan1, plan2));

		final ApiResponse<SearchData> result = this.controller.search(startsAt, endsAt, 50, 0);

		assertNotNull(result);
		assertNotNull(result.data());
		assertNotNull(result.data().events());
		assertEquals(2, result.data().events().size());

		final var captor = ArgumentCaptor.forClass(PlanBetweenDatesQuery.class);
		verify(this.planBetweenDatesQueryHandler).handle(captor.capture());

		final var captured = captor.getValue();
		assertEquals(Optional.of(Instant.parse("2026-01-01T00:00:00Z")), captured.getStartsAt());
		assertEquals(Optional.of(Instant.parse("2026-02-01T00:00:00Z")), captured.getEndsAt());
		assertEquals(50, captured.getLimit());
		assertEquals(0, captured.getOffset());

		verify(this.apiProperties).zoneIdOrUtc();
		verify(this.bulkheadExecutor).execute(any(Supplier.class));
		verifyNoMoreInteractions(this.planBetweenDatesQueryHandler, this.apiProperties, this.bulkheadExecutor);
	}

	@Test
	void search_withoutDates_ok() {
		this.stubBulkheadPassthrough();
		when(this.apiProperties.zoneIdOrUtc()).thenReturn(ZoneId.of("UTC"));

		when(this.planBetweenDatesQueryHandler.handle(any(PlanBetweenDatesQuery.class)))
				.thenReturn(Collections.emptyList());

		final ApiResponse<SearchData> result = this.controller.search(null, null, 50, 0);

		assertNotNull(result);
		assertNotNull(result.data());
		assertNotNull(result.data().events());
		assertEquals(0, result.data().events().size());

		final var captor = ArgumentCaptor.forClass(PlanBetweenDatesQuery.class);
		verify(this.planBetweenDatesQueryHandler).handle(captor.capture());

		final var captured = captor.getValue();
		assertEquals(Optional.empty(), captured.getStartsAt());
		assertEquals(Optional.empty(), captured.getEndsAt());
		assertEquals(50, captured.getLimit());
		assertEquals(0, captured.getOffset());

		verify(this.apiProperties).zoneIdOrUtc();
		verify(this.bulkheadExecutor).execute(any(Supplier.class));
		verifyNoMoreInteractions(this.planBetweenDatesQueryHandler, this.apiProperties, this.bulkheadExecutor);
	}

	@Test
	void search_acceptsLocalDateTimeWithoutOffset_assumesConfiguredZone() {
		this.stubBulkheadPassthrough();
		when(this.apiProperties.zoneIdOrUtc()).thenReturn(ZoneId.of("Europe/Madrid")); // UTC+1 in Jan

		when(this.planBetweenDatesQueryHandler.handle(any(PlanBetweenDatesQuery.class)))
				.thenReturn(Collections.emptyList());

		final ApiResponse<SearchData> result = this.controller.search("2026-01-01T00:00:00", "2026-01-02T00:00:00", 50,
				0);

		assertNotNull(result);

		final var captor = ArgumentCaptor.forClass(PlanBetweenDatesQuery.class);
		verify(this.planBetweenDatesQueryHandler).handle(captor.capture());

		final var captured = captor.getValue();
		assertEquals(Optional.of(Instant.parse("2025-12-31T23:00:00Z")), captured.getStartsAt());
		assertEquals(Optional.of(Instant.parse("2026-01-01T23:00:00Z")), captured.getEndsAt());

		verify(this.apiProperties).zoneIdOrUtc();
		verify(this.bulkheadExecutor).execute(any(Supplier.class));
		verifyNoMoreInteractions(this.planBetweenDatesQueryHandler, this.apiProperties, this.bulkheadExecutor);
	}

	@Test
	void search_mapsPlansToEventSummary_usingConfiguredZone() {
		this.stubBulkheadPassthrough();
		when(this.apiProperties.zoneIdOrUtc()).thenReturn(ZoneId.of("UTC"));

		final var plan = mock(PlanView.class);
		final UUID id = UUID.randomUUID();
		when(plan.id()).thenReturn(id);
		when(plan.title()).thenReturn("Jazz");
		when(plan.startsAt()).thenReturn(Instant.parse("2026-01-20T19:30:00Z"));
		when(plan.endsAt()).thenReturn(Instant.parse("2026-01-20T21:30:00Z"));
		when(plan.minPrice()).thenReturn(null);
		when(plan.maxPrice()).thenReturn(null);

		when(this.planBetweenDatesQueryHandler.handle(any(PlanBetweenDatesQuery.class))).thenReturn(List.of(plan));

		final ApiResponse<SearchData> result = this.controller.search("2026-01-01T00:00:00Z", "2026-12-31T23:59:59Z",
				50, 0);

		assertNotNull(result);
		assertNotNull(result.data());

		final List<EventSummary> events = result.data().events();
		assertEquals(1, events.size());

		final var expected = EventSummary.from(plan, ZoneId.of("UTC"));
		assertEquals(expected, events.get(0));

		verify(this.planBetweenDatesQueryHandler).handle(any(PlanBetweenDatesQuery.class));
		verify(this.apiProperties).zoneIdOrUtc();
		verify(this.bulkheadExecutor).execute(any(Supplier.class));
		verifyNoMoreInteractions(this.planBetweenDatesQueryHandler, this.apiProperties, this.bulkheadExecutor);
	}

	@Test
	void search_invalidDateTime_throwsIllegalArgumentException() {
		when(this.apiProperties.zoneIdOrUtc()).thenReturn(ZoneId.of("UTC"));

		final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> this.controller.search("not-a-date", null, 50, 0));

		assertTrue(ex.getMessage().contains("starts_at"));

		verify(this.apiProperties).zoneIdOrUtc();
		verifyNoInteractions(this.planBetweenDatesQueryHandler);
		verifyNoInteractions(this.bulkheadExecutor);
		verifyNoMoreInteractions(this.apiProperties);
	}
}
