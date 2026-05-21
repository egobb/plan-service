package com.egobb.plan.service.infrastructure.rest.adapter.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "provider")
public record ProviderProperties(String id,

		/**
		 * Base URL for the provider (e.g. https://provider.example.com).
		 * Used together with snapshotPath.
		 */
		String baseUrl,

		/**
		 * Snapshot path (e.g. /api/events). Used together with baseUrl.
		 */
		String snapshotPath,

		/**
		 * Full snapshot URL (e.g. https://provider.../api/events). If present,
		 * overrides baseUrl+snapshotPath.
		 */
		String snapshotUrl,

		/**
		 * Legacy field kept for backward compatibility. It is treated as an alias of
		 * snapshotUrl.
		 */
		@Deprecated String url,

		Duration timeout, Duration connectTimeout, Retry retry) {

	public ProviderProperties {
		// record canonical constructor
	}

	public String idOrDefault() {
		return (this.id == null || this.id.isBlank()) ? "provider-1" : this.id.trim();
	}

	public Duration timeoutOrDefault() {
		return this.timeout == null ? Duration.ofSeconds(5) : this.timeout;
	}

	public Duration connectTimeoutOrDefault() {
		return this.connectTimeout == null ? Duration.ofSeconds(2) : this.connectTimeout;
	}

	public Retry retryOrDefault() {
		return this.retry == null
				? new Retry(3, Duration.ofMillis(200), Duration.ofSeconds(2), 0.2, false)
				: this.retry;
	}

	/**
	 * Resolves the final snapshot URI: - snapshotUrl if present - legacy url
	 * (alias) if present - baseUrl + snapshotPath otherwise
	 */
	public URI snapshotUri() {
		final String effectiveSnapshotUrl = firstNonBlank(this.snapshotUrl, this.url);
		if (effectiveSnapshotUrl != null) {
			return URI.create(effectiveSnapshotUrl);
		}

		final String bu = requireNonBlank(this.baseUrl, "provider.base-url or provider.snapshot-url/provider.url");
		final String sp = requireNonBlank(this.snapshotPath,
				"provider.snapshot-path or provider.snapshot-url/provider.url");

		// Ensure correct concatenation
		final String base = bu.endsWith("/") ? bu.substring(0, bu.length() - 1) : bu;
		final String path = sp.startsWith("/") ? sp : ("/" + sp);

		return URI.create(base + path);
	}

	private static String firstNonBlank(final String a, final String b) {
		if (a != null && !a.isBlank())
			return a.trim();
		if (b != null && !b.isBlank())
			return b.trim();
		return null;
	}

	private static String requireNonBlank(final String v, final String hint) {
		if (v == null || v.isBlank()) {
			throw new IllegalArgumentException("Missing provider configuration: " + hint);
		}
		return v.trim();
	}

	public record Retry(int maxAttempts, Duration initialBackoff, Duration maxBackoff, Double jitter,
			Boolean retryOnParseErrors) {
		public Retry {
			// record canonical constructor
		}

		public int maxAttempts() {
			return Math.max(1, this.maxAttempts);
		}
	}
}
