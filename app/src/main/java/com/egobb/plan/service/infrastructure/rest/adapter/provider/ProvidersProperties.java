package com.egobb.plan.service.infrastructure.rest.adapter.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "providers")
public record ProvidersProperties(List<ProviderProperties> items) {

	public List<ProviderProperties> itemsOrEmpty() {
		return this.items == null ? List.of() : this.items;
	}
}
