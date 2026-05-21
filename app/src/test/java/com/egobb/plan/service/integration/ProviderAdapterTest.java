package com.egobb.plan.service.integration;

import com.egobb.plan.service.application.ingest.ProviderPlan;
import com.egobb.plan.service.domain.model.plan.SellMode;
import com.egobb.plan.service.infrastructure.rest.adapter.provider.ProviderAdapter;
import com.egobb.plan.service.infrastructure.rest.adapter.provider.ProviderProperties;
import com.egobb.plan.service.infrastructure.rest.adapter.provider.xml.ProviderXmlStreamParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderAdapterTest {

	@Test
	void shouldFetchAndParseSnapshotUsingRealStreamingBody() throws Exception {
		final HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);

		final String xml = """
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

				    <base_plan base_plan_id="1591" sell_mode="online" organizer_company_id="1" title="Los Morancos">
				      <plan plan_start_date="2021-07-31T20:00:00" plan_end_date="2021-07-31T21:20:00" plan_id="1642"
				            sell_from="2021-06-26T00:00:00" sell_to="2021-07-31T19:50:00" sold_out="false">
				        <zone zone_id="186" capacity="14" price="65.00" name="Amfiteatre" numbered="false"/>
				      </plan>
				    </base_plan>
				  </output>
				</planList>
				""";

		server.createContext("/api/events", exchange -> {
			final byte[] body = xml.getBytes();
			exchange.getResponseHeaders().add("Content-Type", "application/xml");
			exchange.sendResponseHeaders(200, body.length);
			try (final OutputStream os = exchange.getResponseBody()) {
				os.write(body);
			}
		});

		server.start();
		try {
			final int port = server.getAddress().getPort();

			final ProviderProperties props = new ProviderProperties("demo-provider", "http://localhost:" + port,
					"/api/events", null, null, Duration.ofSeconds(5), Duration.ofSeconds(2), null);

			final ProviderXmlStreamParser parser = new ProviderXmlStreamParser();
			final ProviderAdapter adapter = new ProviderAdapter(props, RestClient.builder(), parser, null);

			final List<ProviderPlan> out = new ArrayList<>();
			adapter.streamPlans(out::add);

			assertThat(out).hasSize(2);

			// Your ID convention: base_plan_id:plan_id
			assertThat(out.get(0).externalPlanId()).isEqualTo("291:291");
			assertThat(out.get(0).sellMode()).isEqualTo(SellMode.ONLINE);
			assertThat(out.get(0).title()).isEqualTo("Camela en concierto");

			assertThat(out.get(1).externalPlanId()).isEqualTo("1591:1642");
			assertThat(out.get(1).sellMode()).isEqualTo(SellMode.ONLINE);
			assertThat(out.get(1).title()).isEqualTo("Los Morancos");

			// Avoid asserting exact instants here: provider dates don't include timezone
			// and parsing strategy may vary.
			// If you want to assert, do it against LocalDateTime or the exact parsing rule
			// you implemented.
		} finally {
			server.stop(0);
		}
	}
}
