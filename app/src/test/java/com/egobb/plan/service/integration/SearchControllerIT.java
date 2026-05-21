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

@SpringBootTest(properties = {"app.mode=api"})
@AutoConfigureMockMvc
class SearchControllerIT extends PostgresTestcontainerConfig {

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
	void searchEndpointReturnsEvents() throws Exception {
		this.adapter.upsertAll(List.of(
				new PlanUpsert("provider-1", "P-100", "Jazz", Instant.parse("2026-01-20T19:30:00Z"),
						Instant.parse("2026-01-20T21:30:00Z"), new BigDecimal("25.00"), new BigDecimal("55.00"), true,
						SellMode.ONLINE),
				new PlanUpsert("provider-1", "P-200", "Offline plan", Instant.parse("2026-02-02T10:00:00Z"),
						Instant.parse("2026-02-02T13:00:00Z"), new BigDecimal("15.00"), new BigDecimal("30.00"), false,
						SellMode.OFFLINE)),
				Instant.parse("2026-01-16T10:00:00Z"));

		this.mockMvc
				.perform(get("/search").queryParam("starts_at", "2026-01-01T00:00:00Z").queryParam("ends_at",
						"2026-12-31T23:59:59Z"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.events").isArray())
				.andExpect(jsonPath("$.data.events[0].title").value("Jazz"))
				.andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.nullValue()));
	}
}
