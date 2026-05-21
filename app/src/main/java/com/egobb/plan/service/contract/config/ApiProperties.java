package com.egobb.plan.service.contract.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * API boundary configuration.
 * <p>
 * The API accepts ISO-8601 date-times. When a date-time without UTC offset is
 * provided, the service interprets it using the configured timezone. Defaults
 * to UTC for deterministic behavior across deployments.
 */
@ConfigurationProperties(prefix = "api")
public record ApiProperties(String timezone) {

	public ZoneId zoneIdOrUtc() {
		final String tz = (this.timezone == null || this.timezone.isBlank()) ? "UTC" : this.timezone.trim();
		try {
			return ZoneId.of(tz);
		} catch (final DateTimeException ex) {
			// Misconfiguration should not break the runtime path. Fall back to UTC.
			return ZoneId.of("UTC");
		}
	}
}
