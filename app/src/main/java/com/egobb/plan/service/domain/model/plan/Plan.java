package com.egobb.plan.service.domain.model.plan;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Domain model used by the application services.
 *
 * <p>
 * NOTE: This is intentionally free of Spring/JPA annotations.
 */
public final class Plan {

	private final PlanKey key;
	private final String title;
	private final Instant startsAt;
	private final Instant endsAt;
	private final BigDecimal minPrice;
	private final BigDecimal maxPrice;
	private final boolean everOnline;
	private final SellMode lastSellMode;

	private Plan(PlanKey key, String title, Instant startsAt, Instant endsAt, BigDecimal minPrice, BigDecimal maxPrice,
			boolean everOnline, SellMode lastSellMode) {
		this.key = Objects.requireNonNull(key, "key");
		this.title = title;
		this.startsAt = startsAt;
		this.endsAt = endsAt;
		this.minPrice = minPrice;
		this.maxPrice = maxPrice;
		this.everOnline = everOnline;
		this.lastSellMode = lastSellMode == null ? SellMode.UNKNOWN : lastSellMode;
	}

	public static Plan fromSnapshot(PlanKey key, String title, Instant startsAt, Instant endsAt, BigDecimal minPrice,
			BigDecimal maxPrice, SellMode sellMode) {
		// NOTE: Invariants like "everOnline is sticky" are enforced at persistence
		// upsert too.
		final boolean everOnline = sellMode == SellMode.ONLINE;
		return new Plan(key, title, startsAt, endsAt, minPrice, maxPrice, everOnline, sellMode);
	}

	public PlanKey key() {
		return this.key;
	}

	public String title() {
		return this.title;
	}

	public Instant startsAt() {
		return this.startsAt;
	}

	public Instant endsAt() {
		return this.endsAt;
	}

	public BigDecimal minPrice() {
		return this.minPrice;
	}

	public BigDecimal maxPrice() {
		return this.maxPrice;
	}

	public boolean everOnline() {
		return this.everOnline;
	}

	public SellMode lastSellMode() {
		return this.lastSellMode;
	}
}
