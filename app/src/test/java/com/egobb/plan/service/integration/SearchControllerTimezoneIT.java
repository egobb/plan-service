package com.egobb.plan.service.integration;

import com.egobb.plan.service.application.ingest.PlanUpsert;
import com.egobb.plan.service.domain.model.plan.SellMode;
import com.egobb.plan.service.infrastructure.persistence.postgres.PlanPostgresAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.mode=api", "api.timezone=Europe/Madrid"})
@AutoConfigureMockMvc
class SearchControllerTimezoneIT extends PostgresTestcontainerConfig {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	PlanPostgresAdapter adapter;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@BeforeEach
	void cleanDb() {
		this.jdbcTemplate.update("TRUNCATE TABLE plans");
	}

	@Test
	void acceptsLocalDateTimeWithoutOffset_inQueryParams() throws Exception {
		this.adapter.upsertAll(List.of(new PlanUpsert("provider-1", "P-100", "Jazz",
				Instant.parse("2026-01-20T19:30:00Z"), Instant.parse("2026-01-20T21:30:00Z"), new BigDecimal("25.00"),
				new BigDecimal("55.00"), true, SellMode.ONLINE)), Instant.parse("2026-01-16T10:00:00Z"));

		// local date-time (no Z). With api.timezone=Europe/Madrid it must still parse
		// and return 200.
		this.mockMvc
				.perform(get("/search").queryParam("starts_at", "2026-01-01T00:00:00").queryParam("ends_at",
						"2026-12-31T23:59:59"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.nullValue()));
	}

	@Test
	void rendersDateAndTimeInConfiguredTimezone_EuropeMadrid() throws Exception {
		// Europe/Madrid in January is UTC+1.
		// 2026-01-20T23:30:00Z -> 2026-01-21 00:30:00 local
		this.adapter.upsertAll(List.of(new PlanUpsert("provider-1", "P-42", "Late show",
				Instant.parse("2026-01-20T23:30:00Z"), Instant.parse("2026-01-21T00:30:00Z"), new BigDecimal("10.00"),
				new BigDecimal("20.00"), true, SellMode.ONLINE)), Instant.parse("2026-01-16T10:00:00Z"));

		this.mockMvc
				.perform(get("/search").queryParam("starts_at", "2026-01-01T00:00:00Z").queryParam("ends_at",
						"2026-12-31T23:59:59Z"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.events[0].title").value("Late show"))
				.andExpect(jsonPath("$.data.events[0].start_date").value("2026-01-21"))
				.andExpect(jsonPath("$.data.events[0].start_time").value("00:30:00"))
				.andExpect(jsonPath("$.data.events[0].end_date").value("2026-01-21"))
				.andExpect(jsonPath("$.data.events[0].end_time").value("01:30:00"))
				.andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.nullValue()));
	}
}
