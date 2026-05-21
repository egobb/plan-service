package com.egobb.plan.service.infrastructure.rest.adapter.provider;

import com.egobb.plan.service.application.ingest.ProviderPlan;
import com.egobb.plan.service.domain.model.plan.SellMode;
import com.egobb.plan.service.infrastructure.rest.adapter.provider.xml.ProviderXmlStreamParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderAdapterTest {

	private HttpServer server;

	@AfterEach
	void tearDown() {
		if (this.server != null) {
			this.server.stop(0);
			this.server = null;
		}
	}

	@Test
	void shouldStreamPlansUsingRealHttpBodyStreaming() throws Exception {
		this.startServer("/api/events", exchange -> respondXml(exchange, 200, XML_ONE_BASE_PLAN_ONE_PLAN_THREE_ZONES));

		final ProviderProperties props = propsWithRetry("demo-provider", this.baseUrl(), "/api/events", null, null, 1,
				Duration.ofMillis(1), Duration.ofMillis(1), 0.0, false);

		final ProviderAdapter adapter = new ProviderAdapter(props, RestClient.builder(), new ProviderXmlStreamParser(),
				null);

		final List<ProviderPlan> out = new ArrayList<>();
		adapter.streamPlans(out::add);

		assertThat(out).isNotEmpty();
		assertThat(out.get(0).externalPlanId()).isEqualTo("291:291");
		assertThat(out.get(0).title()).isEqualTo("Camela en concierto");
		assertThat(out.get(0).sellMode()).isEqualTo(SellMode.ONLINE);
	}

	@Test
	void shouldPreferSnapshotUrlOverBaseUrlAndSnapshotPath() throws Exception {
		this.startServer("/real/snapshot.xml",
				exchange -> respondXml(exchange, 200, XML_ONE_BASE_PLAN_ONE_PLAN_THREE_ZONES));

		final String snapshotUrl = this.baseUrl() + "/real/snapshot.xml";

		final ProviderProperties props = propsWithRetry("demo-provider", "http://does-not-matter", "   ", snapshotUrl,
				null, 1, Duration.ofMillis(1), Duration.ofMillis(1), 0.0, false);

		final ProviderAdapter adapter = new ProviderAdapter(props, RestClient.builder(), new ProviderXmlStreamParser(),
				null);

		final List<ProviderPlan> out = new ArrayList<>();
		adapter.streamPlans(out::add);

		assertThat(out).isNotEmpty();
		assertThat(out.get(0).externalPlanId()).isEqualTo("291:291");
	}

	@Test
	void shouldThrowIfProviderReturnsEmptyBody() throws Exception {
		this.startServer("/api/events", exchange -> {
			final Headers h = exchange.getResponseHeaders();
			h.add("Content-Type", "application/xml");
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});

		final ProviderProperties props = propsWithRetry("demo-provider", this.baseUrl(), "/api/events", null, null, 1,
				Duration.ofMillis(1), Duration.ofMillis(1), 0.0, false);

		final ProviderAdapter adapter = new ProviderAdapter(props, RestClient.builder(), new ProviderXmlStreamParser(),
				null);

		assertThatThrownBy(() -> adapter.streamPlans(p -> {
		})).isInstanceOf(RuntimeException.class);
	}

	@Test
	void shouldRetryOnTransientErrorsAndEventuallySucceed() throws Exception {
		final AtomicInteger calls = new AtomicInteger();

		this.startServer("/api/events", exchange -> {
			final int n = calls.incrementAndGet();
			if (n == 1) {
				respond(exchange, 500, "boom");
				return;
			}
			respondXml(exchange, 200, XML_ONE_BASE_PLAN_ONE_PLAN_THREE_ZONES);
		});

		final ProviderProperties props = propsWithRetry("demo-provider", this.baseUrl(), "/api/events", null, null, 3,
				Duration.ofMillis(1), Duration.ofMillis(2), 0.0, false);

		final ProviderAdapter adapter = new ProviderAdapter(props, RestClient.builder(), new ProviderXmlStreamParser(),
				null);

		final List<ProviderPlan> out = new ArrayList<>();
		adapter.streamPlans(out::add);

		assertThat(calls.get()).isEqualTo(2);
		assertThat(out).isNotEmpty();
		assertThat(out.get(0).externalPlanId()).isEqualTo("291:291");
	}

	@Test
	void shouldNotRetryOnNonRetryable4xx() throws Exception {
		final AtomicInteger calls = new AtomicInteger();

		this.startServer("/api/events", exchange -> {
			calls.incrementAndGet();
			respond(exchange, 404, "not found");
		});

		final ProviderProperties props = propsWithRetry("demo-provider", this.baseUrl(), "/api/events", null, null, 5,
				Duration.ofMillis(1), Duration.ofMillis(2), 0.0, true);

		final ProviderAdapter adapter = new ProviderAdapter(props, RestClient.builder(), new ProviderXmlStreamParser(),
				null);

		assertThatThrownBy(() -> adapter.streamPlans(p -> {
		})).isInstanceOf(RuntimeException.class);

		assertThat(calls.get()).isEqualTo(1);
	}

	@Test
	void shouldRetryOn429ButNotOnOther4xx() throws Exception {
		final AtomicInteger calls = new AtomicInteger();

		this.startServer("/api/events", exchange -> {
			final int n = calls.incrementAndGet();
			if (n == 1) {
				respond(exchange, 429, "too many requests");
				return;
			}
			respondXml(exchange, 200, XML_ONE_BASE_PLAN_ONE_PLAN_THREE_ZONES);
		});

		final ProviderProperties props = propsWithRetry("demo-provider", this.baseUrl(), "/api/events", null, null, 3,
				Duration.ofMillis(1), Duration.ofMillis(2), 0.0, true);

		final ProviderAdapter adapter = new ProviderAdapter(props, RestClient.builder(), new ProviderXmlStreamParser(),
				null);

		final List<ProviderPlan> out = new ArrayList<>();
		adapter.streamPlans(out::add);

		assertThat(calls.get()).isEqualTo(2);
		assertThat(out).isNotEmpty();
		assertThat(out.get(0).externalPlanId()).isEqualTo("291:291");
	}

	@Test
	void shouldUseRetryDefaultsWhenRetryIsNull() throws Exception {
		final AtomicInteger calls = new AtomicInteger();

		this.startServer("/api/events", exchange -> {
			final int n = calls.incrementAndGet();
			if (n <= 2) {
				respond(exchange, 500, "boom" + n);
				return;
			}
			respondXml(exchange, 200, XML_ONE_BASE_PLAN_ONE_PLAN_THREE_ZONES);
		});

		final ProviderProperties props = new ProviderProperties("demo-provider", this.baseUrl(), "/api/events", null,
				null, Duration.ofSeconds(2), Duration.ofSeconds(2), null);

		final ProviderAdapter adapter = new ProviderAdapter(props, RestClient.builder(), new ProviderXmlStreamParser(),
				null);

		final List<ProviderPlan> out = new ArrayList<>();
		adapter.streamPlans(out::add);

		assertThat(calls.get()).isEqualTo(3);
		assertThat(out).isNotEmpty();
		assertThat(out.get(0).externalPlanId()).isEqualTo("291:291");
	}

	// ---------------- helpers ----------------

	private void startServer(final String path, final com.sun.net.httpserver.HttpHandler handler) throws IOException {
		this.server = HttpServer.create(new InetSocketAddress(0), 0);
		this.server.createContext(path, handler);
		this.server.start();
	}

	private String baseUrl() {
		final int port = this.server.getAddress().getPort();
		return "http://localhost:" + port;
	}

	private static void respondXml(final HttpExchange exchange, final int status, final String xml) throws IOException {
		final byte[] body = xml.getBytes(StandardCharsets.UTF_8);
		final Headers h = exchange.getResponseHeaders();
		h.add("Content-Type", "application/xml");
		exchange.sendResponseHeaders(status, body.length);
		try (final OutputStream os = exchange.getResponseBody()) {
			os.write(body);
		}
	}

	private static void respond(final HttpExchange exchange, final int status, final String text) throws IOException {
		final byte[] body = text.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, body.length);
		try (final OutputStream os = exchange.getResponseBody()) {
			os.write(body);
		}
	}

	private static ProviderProperties propsWithRetry(final String id, final String baseUrl, final String snapshotPath,
			final String snapshotUrl, final String legacyUrl, final int maxAttempts, final Duration initialBackoff,
			final Duration maxBackoff, final Double jitter, final boolean retryOnParseErrors) {
		return new ProviderProperties(id, baseUrl, snapshotPath, snapshotUrl, legacyUrl, Duration.ofSeconds(2),
				Duration.ofSeconds(2),
				new ProviderProperties.Retry(maxAttempts, initialBackoff, maxBackoff, jitter, retryOnParseErrors));
	}

	// ---------------- XML fixture ----------------

	private static final String XML_ONE_BASE_PLAN_ONE_PLAN_THREE_ZONES = """
			<planList xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" version="1.0" xsi:noNamespaceSchemaLocation="planList.xsd">
			  <output>
			    <base_plan base_plan_id="291" sell_mode="online" title="Camela en concierto">
			      <plan plan_start_date="2021-06-30T21:00:00" plan_end_date="2021-06-30T22:00:00" plan_id="291"
			            sell_from="2020-07-01T00:00:00" sell_to="2021-06-30T20:00:00" sold_out="false">
			        <zone zone_id="40" capacity="240" price="20.00" name="Platea" numbered="true"/>
			        <zone zone_id="38" capacity="50"  price="15.00" name="Grada 2" numbered="false"/>
			        <zone zone_id="30" capacity="90"  price="30.00" name="A28" numbered="true"/>
			      </plan>
			    </base_plan>
			  </output>
			</planList>
			""";
}
