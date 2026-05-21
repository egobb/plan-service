package com.egobb.plan.service.contract.controller;

import com.egobb.plan.service.application.command.handler.PlanBetweenDatesQueryHandler;
import com.egobb.plan.service.application.command.query.PlanBetweenDatesQuery;
import com.egobb.plan.service.contract.config.ApiProperties;
import com.egobb.plan.service.contract.controller.dto.ApiResponse;
import com.egobb.plan.service.contract.controller.dto.EventSummary;
import com.egobb.plan.service.contract.controller.dto.SearchData;
import com.egobb.plan.service.contract.resilience.SearchBulkheadExecutor;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@RestController
@Validated
@ConditionalOnProperty(name = "app.mode", havingValue = "api", matchIfMissing = true)
public class SearchController {

	private final PlanBetweenDatesQueryHandler planBetweenDatesQueryHandler;
	private final ApiProperties apiProperties;
	private final SearchBulkheadExecutor bulkheadExecutor;

	@GetMapping("/search")
	public ApiResponse<SearchData> search(@RequestParam(name = "starts_at", required = false) String startsAt,
			@RequestParam(name = "ends_at", required = false) String endsAt,
			@RequestParam(name = "limit", defaultValue = "50") @Min(1) @Max(500) int limit,
			@RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset) {

		final ZoneId zoneId = this.apiProperties.zoneIdOrUtc();
		final Optional<Instant> starts = parseInstantParam("starts_at", startsAt, zoneId);
		final Optional<Instant> ends = parseInstantParam("ends_at", endsAt, zoneId);

		final List<EventSummary> events = this.bulkheadExecutor
				.execute(
						() -> this.planBetweenDatesQueryHandler
								.handle(PlanBetweenDatesQuery.builder().startsAt(starts).endsAt(ends).limit(limit)
										.offset(offset).build())
								.stream().map(v -> EventSummary.from(v, zoneId)).toList());

		return ApiResponse.ok(new SearchData(events));
	}

	/**
	 * Parses a query parameter that may be: - ISO_OFFSET_DATE_TIME (e.g.
	 * 2021-07-21T17:32:28Z or 2021-07-21T17:32:28+02:00) - ISO_LOCAL_DATE_TIME
	 * (e.g. 2021-07-21T17:32:28) -> interpreted in the configured API timezone
	 * (default UTC)
	 */
	private static Optional<Instant> parseInstantParam(final String paramName, final String raw, final ZoneId zoneId) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}

		final String value = raw.trim();

		// 1) Preferred: offset-aware input.
		try {
			return Optional.of(OffsetDateTime.parse(value).toInstant());
		} catch (final DateTimeParseException ignored) {
			// fall through
		}

		// 2) Fallback: local date-time (no offset). Interpret it in the configured API
		// timezone (default UTC).
		try {
			final LocalDateTime ldt = LocalDateTime.parse(value);
			final ZoneId z = (zoneId == null) ? ZoneId.of("UTC") : zoneId;
			return Optional.of(ldt.atZone(z).toInstant());
		} catch (final DateTimeParseException ignored) {
			// fall through
		}

		throw new IllegalArgumentException("Invalid value for parameter '" + paramName
				+ "'. Expected ISO-8601 date-time (e.g. 2021-07-21T17:32:28Z or 2021-07-21T17:32:28)");
	}
}
