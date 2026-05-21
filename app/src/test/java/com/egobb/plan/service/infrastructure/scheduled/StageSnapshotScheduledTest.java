package com.egobb.plan.service.infrastructure.scheduled;

import com.egobb.plan.service.application.command.cmd.StageSnapshotCmd;
import com.egobb.plan.service.application.command.handler.StageSnapshotCmdHandler;
import com.egobb.plan.service.domain.service.StageSnapshotService;
import com.egobb.plan.service.infrastructure.lock.AdvisoryLockService;
import com.egobb.plan.service.infrastructure.rest.adapter.provider.ProviderRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StageSnapshotScheduledTest {

	@Test
	void shouldRunUnderLockPerProvider_andInvokeHandler() {
		final WorkerFetchProperties props = new WorkerFetchProperties(10L, 123, "lock");
		final AdvisoryLockService lockService = mock(AdvisoryLockService.class);
		final StageSnapshotCmdHandler handler = mock(StageSnapshotCmdHandler.class);
		final ProviderRegistry providerRegistry = mock(ProviderRegistry.class);

		// Now StageSnapshotScheduled iterates providers
		when(providerRegistry.providerIds()).thenReturn(List.of("demo-provider"));

		// Now lock is per provider: "lock:demo-provider"
		when(lockService.tryWithLock(eq("lock:demo-provider"), any())).thenAnswer(inv -> {
			((Runnable) inv.getArgument(1)).run();
			return true;
		});

		when(handler.handle(any(StageSnapshotCmd.class)))
				.thenReturn(new StageSnapshotService.StageResult(UUID.randomUUID(), 10));

		final StageSnapshotScheduled scheduled = new StageSnapshotScheduled(props, lockService, handler,
				providerRegistry, null);
		scheduled.run();

		verify(lockService).tryWithLock(eq("lock:demo-provider"), any());

		// verify the command includes providerId + batchSize
		verify(handler).handle(argThat(cmd -> "demo-provider".equals(cmd.providerId()) && cmd.batchSize() == 123));
	}
}
