package com.egobb.plan.service.domain.model.plan;

import java.util.Locale;

public enum SellMode {
	ONLINE, OFFLINE, UNKNOWN;

	public static SellMode from(final String raw) {
		if (raw == null) {
			return UNKNOWN;
		}
		return switch (raw.trim().toLowerCase(Locale.ROOT)) {
			case "online" -> ONLINE;
			case "offline" -> OFFLINE;
			default -> UNKNOWN;
		};
	}

	/**
	 * Canonical string representation for persistence / staging.
	 */
	public String value() {
		return switch (this) {
			case ONLINE -> "online";
			case OFFLINE -> "offline";
			case UNKNOWN -> "unknown";
		};
	}
}
