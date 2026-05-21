package com.egobb.plan.service.shared.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class CacheConfig {

	/**
	 * Short-lived cache for hot /search ranges.
	 *
	 * Goal: absorb bursts and protect Postgres without sacrificing correctness.
	 */
	@Bean
	public CacheManager cacheManager() {
		final CaffeineCacheManager manager = new CaffeineCacheManager("searchHotRanges");
		manager.setCaffeine(Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(3)).maximumSize(2_000));
		return manager;
	}
}
