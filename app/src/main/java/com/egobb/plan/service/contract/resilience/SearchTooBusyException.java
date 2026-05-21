package com.egobb.plan.service.contract.resilience;

/**
 * Thrown when the /search bulkhead is full.
 */
public class SearchTooBusyException extends RuntimeException {

	public SearchTooBusyException(final String message) {
		super(message);
	}
}
