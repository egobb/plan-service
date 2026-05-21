package com.egobb.plan.service.infrastructure.rest.adapter.provider;

import com.egobb.plan.service.domain.port.ProviderPort;
import com.egobb.plan.service.shared.observability.PlanServiceMetrics;
import com.egobb.plan.service.infrastructure.rest.adapter.provider.xml.ProviderXmlStreamParser;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ProviderRegistry {

	private final Map<String, ProviderPort> byId;

	public ProviderRegistry(final RestClient.Builder restClientBuilder, final ProviderXmlStreamParser xmlStreamParser,
			final ProviderProperties singleProviderProperties, final ProvidersProperties providersProperties,
			final PlanServiceMetrics metrics) {

		final List<ProviderProperties> effective = effectiveProviders(singleProviderProperties, providersProperties);
		final Map<String, ProviderPort> tmp = new LinkedHashMap<>();

		int i = 0;
		for (final ProviderProperties p : effective) {
			final String providerId = normalizeProviderId(p.id(), i++);
			if (tmp.containsKey(providerId)) {
				throw new IllegalArgumentException("Duplicate provider id: " + providerId);
			}

			// Ensure provider id is set (copy record overriding id if needed)
			final ProviderProperties normalized = new ProviderProperties(providerId, p.baseUrl(), p.snapshotPath(),
					p.snapshotUrl(), p.url(), p.timeout(), p.connectTimeout(), p.retry());

			tmp.put(providerId, new ProviderAdapter(normalized, restClientBuilder, xmlStreamParser, metrics));
		}

		this.byId = Map.copyOf(tmp);
	}

	public List<String> providerIds() {
		return new ArrayList<>(this.byId.keySet());
	}

	public ProviderPort getRequired(final String providerId) {
		final ProviderPort port = this.byId.get(providerId);
		if (port == null) {
			throw new IllegalArgumentException("Unknown provider id: " + providerId);
		}
		return port;
	}

	private static List<ProviderProperties> effectiveProviders(final ProviderProperties single,
			final ProvidersProperties multi) {
		if (multi != null && !multi.itemsOrEmpty().isEmpty()) {
			return multi.itemsOrEmpty();
		}
		// fallback single
		return List.of(single);
	}

	private static String normalizeProviderId(final String id, final int idx) {
		if (id == null || id.isBlank()) {
			return idx == 0 ? "provider-1" : ("provider-" + (idx + 1));
		}
		return id.trim();
	}
}
