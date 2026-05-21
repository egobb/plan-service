package com.egobb.plan.service.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.mode=api"})
@AutoConfigureMockMvc
class SearchControllerErrorIT extends PostgresTestcontainerConfig {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@BeforeEach
	void cleanDb() {
		this.jdbcTemplate.update("TRUNCATE TABLE plans");
	}

	@Test
	void invalidStartsAt_returnsContractErrorEnvelope() throws Exception {
		this.mockMvc.perform(get("/search").queryParam("starts_at", "not-a-date")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.error.message", not(blankOrNullString())));
	}

	@Test
	void invalidLimit_returnsContractErrorEnvelope() throws Exception {
		this.mockMvc.perform(get("/search").queryParam("limit", "0")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.error.message", not(blankOrNullString())));
	}
}
