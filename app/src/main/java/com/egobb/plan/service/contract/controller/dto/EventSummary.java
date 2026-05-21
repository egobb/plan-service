package com.egobb.plan.service.contract.controller.dto;

import com.egobb.plan.service.application.search.PlanView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

public record EventSummary(UUID id, String title, LocalDate start_date, LocalTime start_time, LocalDate end_date,
		LocalTime end_time, BigDecimal min_price, BigDecimal max_price) {

	public static EventSummary from(final PlanView view, final ZoneId zoneId) {
		final ZoneId z = (zoneId == null) ? ZoneId.of("UTC") : zoneId;

		final LocalDate sd = view.startsAt() == null ? null : view.startsAt().atZone(z).toLocalDate();
		final LocalTime st = view.startsAt() == null ? null : view.startsAt().atZone(z).toLocalTime();

		final LocalDate ed = view.endsAt() == null ? null : view.endsAt().atZone(z).toLocalDate();
		final LocalTime et = view.endsAt() == null ? null : view.endsAt().atZone(z).toLocalTime();

		return new EventSummary(view.id(), view.title(), sd, st, ed, et, view.minPrice(), view.maxPrice());
	}
}
