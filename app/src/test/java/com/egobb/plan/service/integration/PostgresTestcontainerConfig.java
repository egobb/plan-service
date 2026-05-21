package com.egobb.plan.service.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

public abstract class PostgresTestcontainerConfig {

	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16").withDatabaseName("egobb")
			.withUsername("egobb").withPassword("egobb");

	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void registerProps(final DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);

		// Opcional: menos conexiones en IT
		registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
	}
}
