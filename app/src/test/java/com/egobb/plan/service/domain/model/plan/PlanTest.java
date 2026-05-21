package com.egobb.plan.service.domain.model.plan;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class PlanTest {

	@Test
	void fromSnapshot_withOnlineSellMode_setsEverOnlineTrue_andLastSellModeOnline() {
		final var key = org.mockito.Mockito.mock(PlanKey.class);
		final var title = "My plan";
		final var startsAt = Instant.parse("2026-01-01T10:00:00Z");
		final var endsAt = Instant.parse("2026-01-02T10:00:00Z");
		final var minPrice = new BigDecimal("10.50");
		final var maxPrice = new BigDecimal("20.00");

		final var plan = Plan.fromSnapshot(key, title, startsAt, endsAt, minPrice, maxPrice, SellMode.ONLINE);

		assertNotNull(plan);
		assertEquals(key, plan.key());
		assertEquals(title, plan.title());
		assertEquals(startsAt, plan.startsAt());
		assertEquals(endsAt, plan.endsAt());
		assertEquals(minPrice, plan.minPrice());
		assertEquals(maxPrice, plan.maxPrice());
		assertTrue(plan.everOnline());
		assertEquals(SellMode.ONLINE, plan.lastSellMode());
	}

	@Test
	void fromSnapshot_withOfflineSellMode_setsEverOnlineFalse_andLastSellModeOffline() {
		final var key = org.mockito.Mockito.mock(PlanKey.class);

		final var plan = Plan.fromSnapshot(key, "Offline plan", Instant.parse("2026-01-01T10:00:00Z"),
				Instant.parse("2026-01-01T12:00:00Z"), new BigDecimal("5.00"), new BigDecimal("7.00"),
				SellMode.OFFLINE);

		assertNotNull(plan);
		assertFalse(plan.everOnline());
		assertEquals(SellMode.OFFLINE, plan.lastSellMode());
	}

	@Test
	void fromSnapshot_withNullSellMode_defaultsLastSellModeToUnknown_andEverOnlineFalse() {
		final var key = org.mockito.Mockito.mock(PlanKey.class);

		final var plan = Plan.fromSnapshot(key, "Unknown sell mode plan", Instant.parse("2026-01-01T10:00:00Z"), null,
				null, null, null);

		assertNotNull(plan);
		assertFalse(plan.everOnline());
		assertEquals(SellMode.UNKNOWN, plan.lastSellMode());
	}

	@Test
	void fromSnapshot_withNullKey_throws() {
		assertThrows(NullPointerException.class,
				() -> Plan.fromSnapshot(null, "Title", Instant.parse("2026-01-01T10:00:00Z"),
						Instant.parse("2026-01-01T12:00:00Z"), new BigDecimal("1.00"), new BigDecimal("2.00"),
						SellMode.ONLINE));
	}
}
