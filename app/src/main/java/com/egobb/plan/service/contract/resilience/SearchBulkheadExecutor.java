package com.egobb.plan.service.contract.resilience;

import com.egobb.plan.service.shared.observability.PlanServiceMetrics;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Limits in-flight /search requests to protect the DB connection pool.
 *
 * Note: in production, global rate-limiting usually lives at the edge (API
 * gateway / ingress). This is a local guardrail to keep the service stable
 * under load and avoid exhausting Hikari.
 */
@Component
@ConditionalOnProperty(name = "app.mode", havingValue = "api", matchIfMissing = true)
public class SearchBulkheadExecutor {

	private static final String BULKHEAD_NAME = "searchApi";

	private final Bulkhead bulkhead;
	private final PlanServiceMetrics metrics;

	public SearchBulkheadExecutor(final BulkheadRegistry bulkheadRegistry, final PlanServiceMetrics metrics) {
		this.bulkhead = Objects.requireNonNull(bulkheadRegistry, "bulkheadRegistry").bulkhead(BULKHEAD_NAME);
		this.metrics = metrics;
	}

	public <T> T execute(final Supplier<T> supplier) {
		Objects.requireNonNull(supplier, "supplier");
		try {
			return Bulkhead.decorateSupplier(this.bulkhead, supplier).get();
		} catch (final BulkheadFullException e) {
			if (this.metrics != null) {
				this.metrics.recordSearchRejected("bulkhead_full");
			}
			throw new SearchTooBusyException("Too many concurrent /search requests. Try again shortly.");
		}
	}
}
