package com.egobb.plan.service.domain.port;

import com.egobb.plan.service.application.ingest.ProviderPlan;

import java.util.function.Consumer;

/**
 * Port to retrieve provider snapshots.
 *
 * v0.4+: the ingestion pipeline must support massive XML snapshots without
 * loading the whole payload into memory. For that reason, the port streams
 * plans one by one.
 */
public interface ProviderPort {

	/**
	 * Streams the provider snapshot, invoking {@code planConsumer} once per plan.
	 */
	void streamPlans(Consumer<ProviderPlan> planConsumer);
}
