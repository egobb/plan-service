package com.egobb.plan.service.infrastructure.rest.adapter.provider.xml;

import com.egobb.plan.service.application.ingest.ProviderPlan;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ProviderXmlStreamParserTest {

	@Test
	void shouldParsePlansStreaming_fromInlineXml_realProviderFormat() {
		final ProviderXmlStreamParser parser = new ProviderXmlStreamParser();

		final String xmlStr = """
				<planList version="1.0">
				  <output>
				    <base_plan base_plan_id="291" sell_mode="online" title="Camela en concierto">
				      <plan plan_start_date="2021-06-30T21:00:00" plan_end_date="2021-06-30T22:00:00" plan_id="291" sold_out="false">
				        <zone zone_id="40" capacity="240" price="20.00" name="Platea" numbered="true"/>
				        <zone zone_id="38" capacity="50" price="15.00" name="Grada 2" numbered="false"/>
				        <zone zone_id="30" capacity="90" price="30.00" name="A28" numbered="true"/>
				      </plan>
				    </base_plan>

				    <base_plan base_plan_id="1591" sell_mode="offline" title="Los Morancos">
				      <plan plan_start_date="2021-07-31T20:00:00" plan_id="1642" sold_out="false">
				        <zone zone_id="186" capacity="14" price="65.00" name="Amfiteatre" numbered="false"/>
				      </plan>
				    </base_plan>
				  </output>
				</planList>
				""";

		try (final InputStream xml = new ByteArrayInputStream(xmlStr.getBytes(StandardCharsets.UTF_8))) {
			final List<ProviderPlan> plans = new ArrayList<>();
			parser.parse(xml, plans::add);

			assertThat(plans).hasSize(2);

			final ProviderPlan p1 = plans.get(0);
			assertThat(p1.externalPlanId()).isEqualTo("291:291"); // base_plan_id:plan_id
			assertThat(p1.title()).isEqualTo("Camela en concierto");
			assertThat(p1.startsAt()).isEqualTo(Instant.parse("2021-06-30T21:00:00Z")); // provider has no TZ -> UTC
			assertThat(p1.endsAt()).isEqualTo(Instant.parse("2021-06-30T22:00:00Z"));
			assertThat(p1.minPrice()).isEqualByComparingTo(new BigDecimal("15.00"));
			assertThat(p1.maxPrice()).isEqualByComparingTo(new BigDecimal("30.00"));

			final ProviderPlan p2 = plans.get(1);
			assertThat(p2.externalPlanId()).isEqualTo("1591:1642");
			assertThat(p2.title()).isEqualTo("Los Morancos");
			assertThat(p2.startsAt()).isEqualTo(Instant.parse("2021-07-31T20:00:00Z"));
			assertThat(p2.endsAt()).isNull(); // plan_end_date missing => null
			assertThat(p2.minPrice()).isEqualByComparingTo(new BigDecimal("65.00"));
			assertThat(p2.maxPrice()).isEqualByComparingTo(new BigDecimal("65.00"));
		} catch (final Exception e) {
			fail("Test should not throw", e);
		}
	}

	@Test
	void shouldSkipInvalidPlanDates_andContinueStreaming() {
		final ProviderXmlStreamParser parser = new ProviderXmlStreamParser();

		final String xmlStr = """
				<planList version="1.0">
				  <output>
				    <base_plan base_plan_id="A" sell_mode="online" title="Good">
				      <plan plan_start_date="2021-06-30T21:00:00" plan_id="1">
				        <zone zone_id="1" capacity="1" price="10.00" name="Z" numbered="true"/>
				      </plan>
				    </base_plan>

				    <base_plan base_plan_id="B" sell_mode="online" title="Bad">
				      <plan plan_start_date="2021-09-31T20:00:00" plan_id="2">
				        <zone zone_id="1" capacity="1" price="10.00" name="Z" numbered="true"/>
				      </plan>
				    </base_plan>

				    <base_plan base_plan_id="C" sell_mode="offline" title="Good2">
				      <plan plan_start_date="2021-07-31T20:00:00" plan_id="3">
				        <zone zone_id="1" capacity="1" price="20.00" name="Z" numbered="true"/>
				      </plan>
				    </base_plan>
				  </output>
				</planList>
				""";

		try (final InputStream xml = new ByteArrayInputStream(xmlStr.getBytes(StandardCharsets.UTF_8))) {
			final List<ProviderPlan> plans = new ArrayList<>();
			parser.parse(xml, plans::add);

			assertThat(plans).hasSize(2);
			assertThat(plans.get(0).externalPlanId()).isEqualTo("A:1");
			assertThat(plans.get(1).externalPlanId()).isEqualTo("C:3");
		} catch (final Exception e) {
			fail("Test should not throw", e);
		}
	}

	@Test
	void shouldThrowIllegalArgumentException_whenXmlIsTotallyInvalid() {
		final ProviderXmlStreamParser parser = new ProviderXmlStreamParser();

		final String xmlStr = "<not-xml";

		final InputStream xml = new ByteArrayInputStream(xmlStr.getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> parser.parse(xml, p -> {
		})).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid provider XML snapshot.");
	}
}
