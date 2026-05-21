package com.egobb.plan.service.shared.config;

import com.egobb.plan.service.contract.config.ApiProperties;
import com.egobb.plan.service.infrastructure.rest.adapter.provider.ProviderProperties;
import com.egobb.plan.service.infrastructure.rest.adapter.provider.ProvidersProperties;
import com.egobb.plan.service.infrastructure.scheduled.WorkerFetchProperties;
import com.egobb.plan.service.infrastructure.scheduled.WorkerProcessProperties;
import com.egobb.plan.service.infrastructure.scheduled.WorkerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
@EnableConfigurationProperties({ProviderProperties.class, ProvidersProperties.class, WorkerProperties.class,
		WorkerFetchProperties.class, WorkerProcessProperties.class, ApiProperties.class})
public class AppConfig {
}
