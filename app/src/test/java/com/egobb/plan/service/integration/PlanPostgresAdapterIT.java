package com.egobb.plan.service.integration;

import com.egobb.plan.service.application.ingest.PlanUpsert;
import com.egobb.plan.service.domain.model.plan.SellMode;
import com.egobb.plan.service.infrastructure.persistence.postgres.PlanPostgresAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {"app.mode=api"})
class PlanPostgresAdapterIT extends PostgresTestcontainerConfig {

	@Autowired
	PlanPostgresAdapter adapter;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@BeforeEach
	void cleanDb() {
		this.jdbcTemplate.update("TRUNCATE TABLE plans");
	}

	@Test
	void upsertAndSearchRoundTripWorks() {
		final Instant now = Instant.parse("2026-01-16T10:00:00Z");

		this.adapter.upsertAll(List.of(
				new PlanUpsert("provider-1", "P-1", "A", Instant.parse("2026-01-20T10:00:00Z"),
						Instant.parse("2026-01-20T11:00:00Z"), new BigDecimal("10.00"), new BigDecimal("20.00"), true,
						SellMode.ONLINE),
				new PlanUpsert("provider-1", "P-2", "B", Instant.parse("2026-02-01T10:00:00Z"),
						Instant.parse("2026-02-01T11:00:00Z"), new BigDecimal("15.00"), new BigDecimal("30.00"), false,
						SellMode.OFFLINE)),
				now);

		final var result = this.adapter.search(Optional.of(Instant.parse("2026-01-01T00:00:00Z")),
				Optional.of(Instant.parse("2026-12-31T23:59:59Z")), 50, 0);
		// Only plans that have ever been ONLINE must be returned.
		assertEquals(1, result.size());

		final var filtered = this.adapter.search(Optional.of(Instant.parse("2026-02-01T00:00:00Z")),
				Optional.of(Instant.parse("2026-12-31T23:59:59Z")), 50, 0);
		assertEquals(0, filtered.size());
	}

	@Test
	void planStillShowsUpAfterDisappearingFromLaterSnapshots_withLatestKnownValues() {
		final Instant t1 = Instant.parse("2026-01-16T10:00:00Z");
		final Instant t2 = Instant.parse("2026-01-16T10:05:00Z");
		final Instant t3 = Instant.parse("2026-01-16T10:10:00Z");

		// Snapshot #1: plan is ONLINE.
		this.adapter.upsertAll(List.of(new PlanUpsert("provider-1", "P-42", "Old title",
				Instant.parse("2026-02-10T10:00:00Z"), Instant.parse("2026-02-10T11:00:00Z"), new BigDecimal("10.00"),
				new BigDecimal("20.00"), true, SellMode.ONLINE)), t1);

		// Snapshot #2: same plan is now OFFLINE (ever_online must stay TRUE), and title
		// changes.
		this.adapter.upsertAll(List.of(new PlanUpsert("provider-1", "P-42", "New title",
				Instant.parse("2026-02-10T10:00:00Z"), Instant.parse("2026-02-10T11:00:00Z"), new BigDecimal("10.00"),
				new BigDecimal("20.00"), false, SellMode.OFFLINE)), t2);

		// Snapshot #3: plan disappears -> we simply don't upsert it.
		this.adapter.upsertAll(List.of(), t3);

		final var result = this.adapter.search(Optional.of(Instant.parse("2026-01-01T00:00:00Z")),
				Optional.of(Instant.parse("2026-12-31T23:59:59Z")), 50, 0);
		assertEquals(1, result.size());
		assertEquals("New title", result.get(0).title());
	}
}
