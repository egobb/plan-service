package com.egobb.plan.service.infrastructure.scheduled;

import com.egobb.plan.service.application.command.cmd.ProcessStagedPlansCmd;
import com.egobb.plan.service.application.command.handler.ProcessStagedPlansCmdHandler;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProcessStagedPlansScheduledTest {

	@Test
	void shouldInvokeHandlerWithoutDistributedLock() {
		final WorkerProcessProperties props = new WorkerProcessProperties(10L, 50, 7, 1000L, 60000L);
		final ProcessStagedPlansCmdHandler handler = mock(ProcessStagedPlansCmdHandler.class);

		when(handler.handle(any(ProcessStagedPlansCmd.class))).thenReturn(123);

		final ProcessStagedPlansScheduled scheduled = new ProcessStagedPlansScheduled(props, handler, null);
		scheduled.run();
		verify(handler).handle(any(ProcessStagedPlansCmd.class));
	}
}
