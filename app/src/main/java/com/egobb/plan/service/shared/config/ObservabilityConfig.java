package com.egobb.plan.service.shared.config;

import com.egobb.plan.service.shared.observability.PlanServiceMetrics;
import com.egobb.plan.service.shared.observability.CorrelationIdFilter;
import com.egobb.plan.service.shared.observability.SearchAccessLogFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Observability wiring: - Correlation id via MDC + response header - Low-noise
 * access logs for /search - Custom business metrics wrapper
 */
@Configuration
public class ObservabilityConfig {

	@Bean
	public PlanServiceMetrics planServiceMetrics(final MeterRegistry meterRegistry) {
		return new PlanServiceMetrics(meterRegistry);
	}

	@Bean
	public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
		final FilterRegistrationBean<CorrelationIdFilter> frb = new FilterRegistrationBean<>();
		frb.setFilter(new CorrelationIdFilter());
		frb.setOrder(1);
		return frb;
	}

	@Bean
	public FilterRegistrationBean<SearchAccessLogFilter> searchAccessLogFilter() {
		final long threshold = Long.parseLong(System.getenv().getOrDefault("SEARCH_SLOW_LOG_MS", "200"));
		final FilterRegistrationBean<SearchAccessLogFilter> frb = new FilterRegistrationBean<>();
		frb.setFilter(new SearchAccessLogFilter(threshold));
		frb.setOrder(2);
		return frb;
	}
}
