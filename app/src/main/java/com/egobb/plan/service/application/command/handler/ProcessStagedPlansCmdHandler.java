package com.egobb.plan.service.application.command.handler;

import com.egobb.plan.service.application.command.cmd.ProcessStagedPlansCmd;
import com.egobb.plan.service.domain.service.ProcessStagedPlansService;
import com.egobb.plan.service.shared.command.CommandHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProcessStagedPlansCmdHandler implements CommandHandler<ProcessStagedPlansCmd, Integer> {

	private final ProcessStagedPlansService processStagedPlansService;

	@Override
	public Integer handle(final ProcessStagedPlansCmd command) {
		return this.processStagedPlansService.processBatch(command.batchSize(), command.maxAttempts());
	}
}
