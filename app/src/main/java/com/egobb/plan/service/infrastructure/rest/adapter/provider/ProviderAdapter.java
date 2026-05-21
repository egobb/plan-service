package com.egobb.plan.service.infrastructure.rest.adapter.provider;

import com.egobb.plan.service.application.ingest.ProviderPlan;
import com.egobb.plan.service.domain.port.ProviderPort;
import com.egobb.plan.service.infrastructure.rest.adapter.provider.xml.ProviderXmlStreamParser;
import com.egobb.plan.service.shared.observability.PlanServiceMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Slf4j
public class ProviderAdapter implements ProviderPort {

	private final ProviderProperties providerProperties;
	private final RestClient restClient;
	private final ProviderXmlStreamParser xmlStreamParser;

	private final PlanServiceMetrics metrics;

	public ProviderAdapter(final ProviderProperties providerProperties, final RestClient.Builder restClientBuilder,
			final ProviderXmlStreamParser xmlStreamParser, final PlanServiceMetrics metrics) {
		this.providerProperties = providerProperties;

		final SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout((int) Math.max(0L, providerProperties.connectTimeoutOrDefault().toMillis()));
		requestFactory.setReadTimeout((int) Math.max(0L, providerProperties.timeoutOrDefault().toMillis()));

		this.restClient = restClientBuilder.requestFactory(requestFactory).build();
		this.xmlStreamParser = xmlStreamParser;
		this.metrics = metrics;
	}

	@Override
	public void streamPlans(final Consumer<ProviderPlan> planConsumer) {
		final ProviderProperties.Retry retry = this.providerProperties.retryOrDefault();
		final int maxAttempts = Math.max(1, retry.maxAttempts());
		Duration backoff = retry.initialBackoff();

		final String providerId = this.providerProperties.idOrDefault();
		final URI snapshotUri = this.providerProperties.snapshotUri();

		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			final Instant start = Instant.now();
			final AtomicLong plansCount = new AtomicLong(0L);
			try {
				log.info("Fetching provider snapshot (attempt {}/{}): providerId={} uri={}", attempt, maxAttempts,
						providerId, snapshotUri);

				// streaming: parse directly from HTTP response body InputStream.
				this.restClient.get().uri(snapshotUri.toString())
						.accept(MediaType.APPLICATION_XML, MediaType.TEXT_XML, MediaType.APPLICATION_OCTET_STREAM)
						.exchange((request, response) -> {
							final int status = response.getStatusCode().value();
							if (status < 200 || status >= 300) {
								// Consume body if needed? not required; throw.
								throw new RestClientResponseException("Non-2xx status from provider: " + status, status,
										response.getStatusText(), response.getHeaders(), null, null);
							}

							try (final InputStream is = response.getBody()) {
								if (is == null) {
									throw new IllegalStateException("Provider returned an empty body.");
								}
								this.xmlStreamParser.parse(is, plan -> {
									plansCount.incrementAndGet();
									planConsumer.accept(plan);
								});
								return null;
							}
						});

				final Duration dur = Duration.between(start, Instant.now());
				final long ms = dur.toMillis();
				if (this.metrics != null) {
					this.metrics.recordProviderSnapshot(providerId, "success", plansCount.get(), dur);
				}
				log.info(
						"Provider snapshot fetched & parsed successfully: providerId={} uri={} durationMs={} plansCount={}",
						providerId, snapshotUri, ms, plansCount.get());
				return;

			} catch (final Exception e) {
				final Duration dur = Duration.between(start, Instant.now());
				final long ms = dur.toMillis();
				if (this.metrics != null) {
					this.metrics.recordProviderSnapshot(providerId, "failure", 0L, dur);
				}

				// Don't retry non-retryable 4xx (except 429)
				if (e instanceof RestClientResponseException rre) {
					final int status = rre.getStatusCode().value();
					final boolean is4xx = status >= 400 && status < 500;
					final boolean isRetryable4xx = status == 429;

					log.warn("Provider HTTP error: providerId={} uri={} status={} durationMs={} err={}", providerId,
							snapshotUri, status, ms, e.toString());

					if (is4xx && !isRetryable4xx) {
						log.error("Non-retryable provider HTTP error. Aborting: providerId={} uri={} status={}",
								providerId, snapshotUri, status, e);
						throw new RuntimeException("Error streaming provider snapshot", e);
					}
				} else {
					log.warn("Provider snapshot attempt failed: providerId={} uri={} durationMs={} err={}", providerId,
							snapshotUri, ms, e.toString());
				}

				final boolean parseError = isLikelyParseError(e);
				final boolean shouldRetry = attempt < maxAttempts
						&& (!parseError || Boolean.TRUE.equals(retry.retryOnParseErrors()));

				if (!shouldRetry) {
					log.error("Provider snapshot failed after {} attempts: providerId={} uri={}", attempt, providerId,
							snapshotUri, e);
					throw new RuntimeException("Error streaming provider snapshot", e);
				}

				sleepWithJitter(backoff, retry.jitter());
				backoff = nextBackoff(backoff, retry.maxBackoff());
			}
		}

		throw new RuntimeException("Error streaming provider snapshot");
	}

	private static boolean isLikelyParseError(final Exception e) {
		Throwable t = e;
		while (t != null) {
			final String name = t.getClass().getName();
			if (name.contains("XMLStream") || name.contains("SAX") || name.contains("Parser")) {
				return true;
			}
			t = t.getCause();
		}
		return false;
	}

	private static void sleepWithJitter(final Duration base, final Double jitterFraction) {
		final long baseMs = Math.max(0L, base == null ? 0L : base.toMillis());
		final double jf = (jitterFraction == null) ? 0.0 : Math.max(0.0, jitterFraction);

		if (baseMs == 0L)
			return;
		if (jf == 0.0) {
			sleepQuietly(baseMs);
			return;
		}

		final double jitter = baseMs * jf;
		if (jitter <= 0.0) {
			sleepQuietly(baseMs);
			return;
		}

		final long delta = (long) ThreadLocalRandom.current().nextDouble(-jitter, jitter);
		final long sleepMs = Math.max(0L, baseMs + delta);
		sleepQuietly(sleepMs);
	}

	private static void sleepQuietly(final long millis) {
		if (millis <= 0L)
			return;
		try {
			Thread.sleep(millis);
		} catch (final InterruptedException ie) {
			Thread.currentThread().interrupt();
		}
	}

	private static Duration nextBackoff(final Duration current, final Duration max) {
		final Duration safeCurrent = current == null ? Duration.ZERO : current;
		final Duration doubled = safeCurrent.multipliedBy(2);
		if (max == null)
			return doubled;
		return doubled.compareTo(max) > 0 ? max : doubled;
	}
}
