package com.egobb.plan.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
@EnableScheduling
public class PlanServiceApplication {

	public static void main(String[] args) {
		final String appMode = System.getenv().getOrDefault("APP_MODE", "api");
		final Map<String, Object> props = new HashMap<>();

		// We use the app mode as a Spring profile to load profile-specific
		// configuration files:
		// - application-api.yml (optional)
		// - application-worker-fetch.yml
		// - application-worker-process.yml
		props.put("spring.profiles.active", appMode);

		// We also expose the mode as a regular property for ConditionalOnProperty.
		props.put("app.mode", appMode);

		final SpringApplication app = new SpringApplication(PlanServiceApplication.class);
		app.setDefaultProperties(props);
		app.run(args);
	}
}
