package com.egobb.plan.service.infrastructure.rest.adapter.provider.xml;

import com.egobb.plan.service.application.ingest.ProviderPlan;
import com.egobb.plan.service.domain.model.plan.SellMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Streaming XML parser (StAX) for provider snapshots.
 *
 * Provider real format: <planList> <output>
 * <base_plan base_plan_id="..." sell_mode="..." title="...">
 * <plan plan_id="..." plan_start_date="..." plan_end_date="..."> <zone
 * price="20.00" .../> </plan> </base_plan> </output> </planList>
 */
@Component
@Slf4j
public class ProviderXmlStreamParser {

	private final XMLInputFactory factory;

	public ProviderXmlStreamParser() {
		this.factory = XMLInputFactory.newFactory();

		// Hardening: prevent XXE / entity expansion.
		// StAX implementations differ a bit, so we set both the standard constants
		// and the well-known property names.
		this.setIfSupported(XMLInputFactory.SUPPORT_DTD, false);
		this.setIfSupported("javax.xml.stream.supportDTD", false);
		this.setIfSupported(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
		this.setIfSupported("javax.xml.stream.isSupportingExternalEntities", false);
		this.setIfSupported(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
		this.setIfSupported(XMLInputFactory.IS_COALESCING, true);
	}

	public void parse(final InputStream inputStream, final Consumer<ProviderPlan> consumer) {
		try {
			final XMLStreamReader reader = this.factory.createXMLStreamReader(inputStream);

			// Context from <base_plan ...>
			String currentBasePlanId = null;
			String currentTitle = null;
			SellMode currentSellMode = null;

			while (reader.hasNext()) {
				final int event = reader.next();

				if (event == XMLStreamConstants.START_ELEMENT) {
					final String name = reader.getLocalName();

					if ("base_plan".equals(name)) {
						currentBasePlanId = attr(reader, "base_plan_id");
						currentTitle = attr(reader, "title");
						currentSellMode = sellModeOrNull(attr(reader, "sell_mode"));

					} else if ("plan".equals(name)) {
						if (currentBasePlanId != null && currentTitle != null) {
							final ProviderPlan plan = this.readPlan(reader, currentBasePlanId, currentTitle,
									currentSellMode);
							if (plan != null) {
								consumer.accept(plan);
							}
						} else {
							// Defensive: skip malformed structure
							skipToEndElement(reader, "plan");
						}
					}
				}

				if (event == XMLStreamConstants.END_ELEMENT && "base_plan".equals(reader.getLocalName())) {
					currentBasePlanId = null;
					currentTitle = null;
					currentSellMode = null;
				}
			}

			reader.close();
		} catch (final Exception e) {
			throw new IllegalArgumentException("Invalid provider XML snapshot.", e);
		}
	}

	private void setIfSupported(final String property, final Object value) {
		try {
			if (this.factory.isPropertySupported(property)) {
				this.factory.setProperty(property, value);
			}
		} catch (final Exception ignored) {
			// Ignore: some implementations throw even if supported.
		}
	}

	private ProviderPlan readPlan(final XMLStreamReader reader, final String basePlanId, final String title,
			final SellMode sellMode) throws Exception {
		// We are positioned at: START_ELEMENT <plan ...>
		final String planId = attr(reader, "plan_id");

		final Instant startsAt;
		try {
			startsAt = parseProviderInstant(attr(reader, "plan_start_date"));
		} catch (final Exception ex) {
			// Bad provider data: skip this <plan> node but keep streaming the rest
			log.warn("Skipping invalid plan (base_plan_id={}, plan_id={}): invalid plan_start_date. Error={}",
					basePlanId, planId, ex.toString());
			skipToEndElement(reader, "plan");
			return null;
		}

		Instant endsAt;
		try {
			endsAt = parseProviderInstantNullable(attr(reader, "plan_end_date"));
		} catch (final Exception ex) {
			// end date is optional; treat invalid as null
			log.warn("Invalid plan_end_date for plan (base_plan_id={}, plan_id={}). Setting endsAt=null. Error={}",
					basePlanId, planId, ex.toString());
			endsAt = null;
		}

		BigDecimal minPrice = null;
		BigDecimal maxPrice = null;

		while (reader.hasNext()) {
			final int event = reader.next();

			if (event == XMLStreamConstants.START_ELEMENT) {
				final String name = reader.getLocalName();

				if ("zone".equals(name)) {
					final BigDecimal price = bigDecimalOrNull(attr(reader, "price"));
					if (price != null) {
						minPrice = (minPrice == null) ? price : minPrice.min(price);
						maxPrice = (maxPrice == null) ? price : maxPrice.max(price);
					}
				} else {
					// ignore unknown nested elements under <plan>
					skipElement(reader);
				}
			}

			if (event == XMLStreamConstants.END_ELEMENT && "plan".equals(reader.getLocalName())) {
				break;
			}
		}

		if (planId == null || planId.isBlank() || startsAt == null) {
			return null;
		}

		// Avoid collisions: base_plan_id + ":" + plan_id
		final String id = basePlanId + ":" + planId;

		return new ProviderPlan(id, title, sellMode, startsAt, endsAt, minPrice, maxPrice);
	}

	private static String attr(final XMLStreamReader reader, final String name) {
		final String v = reader.getAttributeValue(null, name);
		return v == null ? null : v.trim();
	}

	private static SellMode sellModeOrNull(final String raw) {
		if (raw == null || raw.isBlank())
			return null;
		return SellMode.from(raw);
	}

	private static Instant parseProviderInstant(final String raw) {
		if (raw == null || raw.isBlank())
			return null;
		return Instant.parse(ensureUtcOffset(raw));
	}

	private static Instant parseProviderInstantNullable(final String raw) {
		if (raw == null || raw.isBlank())
			return null;
		return Instant.parse(ensureUtcOffset(raw));
	}

	private static String ensureUtcOffset(final String raw) {
		// Provider sends timestamps without timezone, e.g. 2021-06-30T21:00:00
		// Interpret as UTC by appending 'Z' if no offset is present.
		final String v = raw.trim();
		if (v.endsWith("Z"))
			return v;

		// Detect "+hh:mm" or "-hh:mm" offset at the end
		if (v.length() >= 6) {
			final char sign = v.charAt(v.length() - 6);
			if ((sign == '+' || sign == '-') && v.charAt(v.length() - 3) == ':') {
				return v;
			}
		}

		// No offset => assume UTC
		return v + "Z";
	}

	private static BigDecimal bigDecimalOrNull(final String raw) {
		if (raw == null || raw.isBlank())
			return null;
		return new BigDecimal(raw);
	}

	private static void skipElement(final XMLStreamReader reader) throws Exception {
		// Reader is positioned at START_ELEMENT of the unknown node.
		int depth = 1;
		while (reader.hasNext() && depth > 0) {
			final int ev = reader.next();
			if (ev == XMLStreamConstants.START_ELEMENT)
				depth++;
			else if (ev == XMLStreamConstants.END_ELEMENT)
				depth--;
		}
	}

	private static void skipToEndElement(final XMLStreamReader reader, final String elementName) throws Exception {
		while (reader.hasNext()) {
			final int ev = reader.next();
			if (ev == XMLStreamConstants.END_ELEMENT && elementName.equals(reader.getLocalName())) {
				return;
			}
		}
	}

}
